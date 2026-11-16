#include <jni.h>
#include <stdio.h>
#include <stdint.h>
#include <string.h>

#include "libavformat/avformat.h"
#include "libavcodec/avcodec.h"
#include "libavutil/imgutils.h"
#include "libswscale/swscale.h"
#include "libavcodec/packet.h"
#include "libavutil/error.h"
#include "libavutil/log.h"
#include "libavutil/mathematics.h"

/* Java writes two fragmented MP4 files (encrypted video + encrypted audio).
 * This decrypts them with the ClearKey via the MOV demuxer and interleaves
 * both tracks into one MP4, with no re-encoding. */

typedef struct {
    AVFormatContext *fmt;
    AVPacket *pkt;
    int in_index;
    int out_index;
    int eof;
    int ready;
} Source;

static void describe_error(char *err, size_t errlen, const char *what, int code) {
    if (err == NULL || errlen == 0) {
        return;
    }
    char avbuf[AV_ERROR_MAX_STRING_SIZE];
    av_strerror(code, avbuf, sizeof(avbuf));
    snprintf(err, errlen, "%s: %s", what, avbuf);
}

static int open_input(Source *s, const char *path, const char *key,
                      enum AVMediaType type) {
    AVDictionary *opts = NULL;
    av_dict_set(&opts, "decryption_key", key, 0);
    int ret = avformat_open_input(&s->fmt, path, NULL, &opts);
    av_dict_free(&opts);
    if (ret < 0) {
        return ret;
    }
    for (unsigned i = 0; i < s->fmt->nb_streams; i++) {
        if (s->fmt->streams[i]->codecpar->codec_type == type) {
            s->in_index = (int)i;
            return 0;
        }
    }
    return AVERROR_STREAM_NOT_FOUND;
}

static int copy_stream(AVFormatContext *out, Source *s) {
    AVStream *in = s->fmt->streams[s->in_index];
    AVStream *os = avformat_new_stream(out, NULL);
    if (os == NULL) {
        return AVERROR(ENOMEM);
    }
    int ret = avcodec_parameters_copy(os->codecpar, in->codecpar);
    if (ret < 0) {
        return ret;
    }
    os->codecpar->codec_tag = 0;
    os->time_base = in->time_base;
    s->out_index = os->index;
    return 0;
}

static int fill(Source *s) {
    while (!s->eof && !s->ready) {
        if (av_read_frame(s->fmt, s->pkt) < 0) {
            s->eof = 1;
            break;
        }
        if (s->pkt->stream_index != s->in_index) {
            av_packet_unref(s->pkt);
            continue;
        }
        s->ready = 1;
    }
    return s->ready;
}

/* Returns 0 on success, a negative AVERROR otherwise. Plain C so the host
 * test can call it without a JVM. */
int pbb_remux(const char *vpath, const char *apath, const char *opath,
              const char *key, char *err, size_t errlen) {
    Source src[2];
    memset(src, 0, sizeof(src));
    src[0].in_index = -1;
    src[1].in_index = -1;
    AVFormatContext *out = NULL;
    int ret = AVERROR(EINVAL);

    if (err != NULL && errlen > 0) {
        err[0] = '\0';
    }
    av_log_set_level(AV_LOG_ERROR);
    if (!vpath || !apath || !opath || !key) {
        describe_error(err, errlen, "null argument", AVERROR(EINVAL));
        return AVERROR(EINVAL);
    }

    ret = open_input(&src[0], vpath, key, AVMEDIA_TYPE_VIDEO);
    if (ret < 0) { describe_error(err, errlen, "open video", ret); goto done; }
    ret = open_input(&src[1], apath, key, AVMEDIA_TYPE_AUDIO);
    if (ret < 0) { describe_error(err, errlen, "open audio", ret); goto done; }

    ret = avformat_alloc_output_context2(&out, NULL, "mp4", opath);
    if (ret < 0) { describe_error(err, errlen, "output context", ret); goto done; }
    ret = copy_stream(out, &src[0]);
    if (ret < 0) { describe_error(err, errlen, "video stream", ret); goto done; }
    ret = copy_stream(out, &src[1]);
    if (ret < 0) { describe_error(err, errlen, "audio stream", ret); goto done; }

    ret = avio_open(&out->pb, opath, AVIO_FLAG_WRITE);
    if (ret < 0) { describe_error(err, errlen, "open output", ret); goto done; }

    // DASH tfdt timestamps are absolute (time since the stream started). Without
    // shifting them to zero the muxer writes a huge mvhd/tkhd duration and only
    // hides it behind an edit list, which many players ignore.
    out->avoid_negative_ts = AVFMT_AVOID_NEG_TS_MAKE_ZERO;

    AVDictionary *mux_opts = NULL;
    av_dict_set(&mux_opts, "movflags", "+faststart", 0);
    ret = avformat_write_header(out, &mux_opts);
    av_dict_free(&mux_opts);
    if (ret < 0) { describe_error(err, errlen, "write header", ret); goto done; }

    for (int i = 0; i < 2; i++) {
        src[i].pkt = av_packet_alloc();
        if (src[i].pkt == NULL) {
            ret = AVERROR(ENOMEM);
            describe_error(err, errlen, "packet alloc", ret);
            goto done;
        }
    }

    for (;;) {
        fill(&src[0]);
        fill(&src[1]);
        int best = -1;
        int64_t best_ts = INT64_MAX;
        for (int i = 0; i < 2; i++) {
            if (!src[i].ready) {
                continue;
            }
            AVStream *in = src[i].fmt->streams[src[i].in_index];
            AVStream *os = out->streams[src[i].out_index];
            int64_t ts = src[i].pkt->dts != AV_NOPTS_VALUE
                ? src[i].pkt->dts : src[i].pkt->pts;
            if (ts == AV_NOPTS_VALUE) {
                ts = 0;
            }
            ts = av_rescale_q(ts, in->time_base, os->time_base);
            if (ts < best_ts) {
                best_ts = ts;
                best = i;
            }
        }
        if (best < 0) {
            break;
        }
        Source *s = &src[best];
        AVStream *in = s->fmt->streams[s->in_index];
        AVStream *os = out->streams[s->out_index];
        s->pkt->stream_index = s->out_index;
        av_packet_rescale_ts(s->pkt, in->time_base, os->time_base);
        ret = av_interleaved_write_frame(out, s->pkt);
        if (ret < 0) {
            describe_error(err, errlen, "write packet", ret);
            goto done;
        }
        av_packet_unref(s->pkt);
        s->ready = 0;
    }

    ret = av_write_trailer(out);
    if (ret < 0) {
        describe_error(err, errlen, "write trailer", ret);
    }

done:
    for (int i = 0; i < 2; i++) {
        av_packet_free(&src[i].pkt);
        if (src[i].fmt != NULL) {
            avformat_close_input(&src[i].fmt);
        }
    }
    if (out != NULL) {
        if (out->pb != NULL && !(out->oformat->flags & AVFMT_NOFILE)) {
            avio_closep(&out->pb);
        }
        avformat_free_context(out);
    }
    return ret;
}

JNIEXPORT void JNICALL
Java_com_pbb_live_RecordService_nativeRemux(
    JNIEnv *env, jclass cls, jstring jvideo, jstring jaudio,
    jstring joutput, jstring jkey) {
    (void)cls;
    const char *vpath = (*env)->GetStringUTFChars(env, jvideo, NULL);
    const char *apath = (*env)->GetStringUTFChars(env, jaudio, NULL);
    const char *opath = (*env)->GetStringUTFChars(env, joutput, NULL);
    const char *key = (*env)->GetStringUTFChars(env, jkey, NULL);
    char err[512];

    int ret = pbb_remux(vpath, apath, opath, key, err, sizeof(err));
    if (ret < 0) {
        jclass exc = (*env)->FindClass(env, "java/lang/RuntimeException");
        if (exc != NULL) {
            (*env)->ThrowNew(env, exc, err);
        }
    }

    if (vpath) (*env)->ReleaseStringUTFChars(env, jvideo, vpath);
    if (apath) (*env)->ReleaseStringUTFChars(env, jaudio, apath);
    if (opath) (*env)->ReleaseStringUTFChars(env, joutput, opath);
    if (key)   (*env)->ReleaseStringUTFChars(env, jkey, key);
}


/* ------------------------------------------------------------------ *
 * Stack two finished recordings into one tall MP4. The published files
 * are already decrypted by pbb_remux, so no key is needed here. Both
 * feeds are captured at the same quality, so their frames match 1:1;
 * one frame from each is joined top/bottom into a single H.264 frame.
 * ------------------------------------------------------------------ */

typedef struct {
    AVFormatContext *fmt;
    AVCodecContext *dec;
    int video_index;
    int audio_index;
    AVPacket *pkt;
    int eof;
    AVPacket **audio;
    int audio_count;
    int audio_cap;
} StackInput;

static int stack_open(StackInput *in, const char *path) {
    int ret;
    memset(in, 0, sizeof(*in));
    in->video_index = in->audio_index = -1;
    ret = avformat_open_input(&in->fmt, path, NULL, NULL);
    if (ret < 0) return ret;
    ret = avformat_find_stream_info(in->fmt, NULL);
    if (ret < 0) return ret;
    for (unsigned i = 0; i < in->fmt->nb_streams; i++) {
        AVStream *st = in->fmt->streams[i];
        if (st->codecpar->codec_type == AVMEDIA_TYPE_VIDEO && in->video_index < 0) {
            in->video_index = (int)i;
        }
        if (st->codecpar->codec_type == AVMEDIA_TYPE_AUDIO && in->audio_index < 0) {
            in->audio_index = (int)i;
        }
    }
    if (in->video_index < 0) return AVERROR_STREAM_NOT_FOUND;
    AVStream *vs = in->fmt->streams[in->video_index];
    const AVCodec *codec = avcodec_find_decoder(vs->codecpar->codec_id);
    if (codec == NULL) return AVERROR_DECODER_NOT_FOUND;
    in->dec = avcodec_alloc_context3(codec);
    if (in->dec == NULL) return AVERROR(ENOMEM);
    ret = avcodec_parameters_to_context(in->dec, vs->codecpar);
    if (ret < 0) return ret;
    ret = avcodec_open2(in->dec, codec, NULL);
    if (ret < 0) return ret;
    in->pkt = av_packet_alloc();
    return in->pkt == NULL ? AVERROR(ENOMEM) : 0;
}

/* Reads one decoded video frame. Audio packets on the first input are kept
 * for the output mux instead of being thrown away with the other streams. */
static int stack_read_frame(StackInput *in, AVFrame *frame, int keep_audio) {
    int ret;
    for (;;) {
        ret = avcodec_receive_frame(in->dec, frame);
        if (ret == 0) return 1;
        if (ret == AVERROR_EOF || ret != AVERROR(EAGAIN)) return 0;
        if (in->eof) return 0;
        ret = av_read_frame(in->fmt, in->pkt);
        if (ret < 0) {
            avcodec_send_packet(in->dec, NULL);
            in->eof = 1;
            continue;
        }
        if (in->pkt->stream_index == in->video_index) {
            ret = avcodec_send_packet(in->dec, in->pkt);
            if (ret < 0 && ret != AVERROR(EAGAIN)) {
                in->eof = 1;
            }
        } else if (keep_audio && in->audio_index >= 0
                   && in->pkt->stream_index == in->audio_index) {
            if (in->audio_count == in->audio_cap) {
                int cap = in->audio_cap ? in->audio_cap * 2 : 64;
                AVPacket **grown = av_realloc(in->audio, (size_t)cap * sizeof(*grown));
                if (grown == NULL) {
                    av_packet_unref(in->pkt);
                    in->eof = 1;
                    continue;
                }
                in->audio = grown;
                in->audio_cap = cap;
            }
            in->audio[in->audio_count++] = av_packet_clone(in->pkt);
        }
        av_packet_unref(in->pkt);
    }
}

static AVFrame *stack_yuv420(AVFrame *src, struct SwsContext **ctx) {
    if (src->format == AV_PIX_FMT_YUV420P) {
        return src;
    }
    AVFrame *dst = av_frame_alloc();
    if (dst == NULL) return NULL;
    dst->format = AV_PIX_FMT_YUV420P;
    dst->width = src->width;
    dst->height = src->height;
    if (av_frame_get_buffer(dst, 32) < 0) {
        av_frame_free(&dst);
        return NULL;
    }
    if (*ctx == NULL) {
        *ctx = sws_getContext(src->width, src->height, src->format,
            src->width, src->height, AV_PIX_FMT_YUV420P,
            SWS_FAST_BILINEAR, NULL, NULL, NULL);
    }
    if (*ctx == NULL || sws_scale(*ctx, (const uint8_t * const *)src->data,
            src->linesize, 0, src->height, dst->data, dst->linesize) <= 0) {
        av_frame_free(&dst);
        return NULL;
    }
    return dst;
}

static void stack_rows(uint8_t *dst, int dls, const uint8_t *src, int sls,
                       int bytes, int rows) {
    if (src == NULL) {
        memset(dst, 0, (size_t)dls * rows);
        return;
    }
    for (int y = 0; y < rows; y++) {
        memcpy(dst + (size_t)y * dls, src + (size_t)y * sls, bytes);
    }
}

static void stack_planes(AVFrame *dst, const AVFrame *top, const AVFrame *bottom,
                         int w, int h) {
    /* Each source contributes its full height (h) to the 2h output; the
     * chroma planes contribute h/2 rows each. */
    int rows_y = h;
    int rows_c = h / 2;
    int cw = w / 2;
    stack_rows(dst->data[0], dst->linesize[0],
        top ? top->data[0] : NULL, top ? top->linesize[0] : 0, w, rows_y);
    stack_rows(dst->data[0] + (size_t)rows_y * dst->linesize[0], dst->linesize[0],
        bottom ? bottom->data[0] : NULL, bottom ? bottom->linesize[0] : 0, w, rows_y);
    stack_rows(dst->data[1], dst->linesize[1],
        top ? top->data[1] : NULL, top ? top->linesize[1] : 0, cw, rows_c);
    stack_rows(dst->data[1] + (size_t)rows_c * dst->linesize[1], dst->linesize[1],
        bottom ? bottom->data[1] : NULL, bottom ? bottom->linesize[1] : 0, cw, rows_c);
    stack_rows(dst->data[2], dst->linesize[2],
        top ? top->data[2] : NULL, top ? top->linesize[2] : 0, cw, rows_c);
    stack_rows(dst->data[2] + (size_t)rows_c * dst->linesize[2], dst->linesize[2],
        bottom ? bottom->data[2] : NULL, bottom ? bottom->linesize[2] : 0, cw, rows_c);
}

static void stack_flush(AVCodecContext *enc, AVFormatContext *out, AVStream *vst,
                        AVPacket *pkt) {
    int ret;
    avcodec_send_frame(enc, NULL);
    while ((ret = avcodec_receive_packet(enc, pkt)) == 0) {
        pkt->stream_index = vst->index;
        av_packet_rescale_ts(pkt, enc->time_base, vst->time_base);
        av_interleaved_write_frame(out, pkt);
        av_packet_unref(pkt);
    }
}

int pbb_stack(const char *top_path, const char *bottom_path, const char *opath,
              char *err, size_t errlen) {
    StackInput top, bottom;
    AVFormatContext *out = NULL;
    AVCodecContext *enc = NULL;
    AVFrame *ft = NULL, *fb = NULL, *frame = NULL;
    AVPacket *pkt = NULL;
    struct SwsContext *sws_t = NULL, *sws_b = NULL;
    AVStream *vst = NULL, *ast = NULL;
    AVDictionary *mux_opts = NULL, *enc_opts = NULL;
    int w, h, ret = AVERROR(EINVAL), wrote_header = 0;
    int64_t pts = 0;

    if (err != NULL && errlen > 0) err[0] = '\0';
    av_log_set_level(AV_LOG_ERROR);
    if (top_path == NULL || bottom_path == NULL || opath == NULL) {
        describe_error(err, errlen, "null argument", AVERROR(EINVAL));
        return AVERROR(EINVAL);
    }

    ret = stack_open(&top, top_path);
    if (ret < 0) { describe_error(err, errlen, "open main recording", ret); goto done; }
    ret = stack_open(&bottom, bottom_path);
    if (ret < 0) { describe_error(err, errlen, "open dining recording", ret); goto done; }

    AVStream *vsrc = top.fmt->streams[top.video_index];
    AVStream *vsrc2 = bottom.fmt->streams[bottom.video_index];
    w = vsrc->codecpar->width;
    h = vsrc->codecpar->height;
    if (w <= 0 || h <= 0 || w % 2 != 0 || h % 2 != 0
        || vsrc2->codecpar->width != w || vsrc2->codecpar->height != h) {
        describe_error(err, errlen, "feed resolutions differ", AVERROR(EINVAL));
        goto done;
    }

    const AVCodec *codec = avcodec_find_encoder(AV_CODEC_ID_H264);
    if (codec == NULL) {
        describe_error(err, errlen, "no H.264 encoder", AVERROR_ENCODER_NOT_FOUND);
        goto done;
    }
    enc = avcodec_alloc_context3(codec);
    if (enc == NULL) { ret = AVERROR(ENOMEM); goto done; }
    enc->width = w;
    enc->height = h * 2;
    enc->pix_fmt = AV_PIX_FMT_YUV420P;
    AVRational fps = vsrc->avg_frame_rate;
    if (fps.num <= 0 || fps.den <= 0) fps = (AVRational){ 30, 1 };
    enc->framerate = fps;
    enc->time_base = av_inv_q(fps);
    enc->bit_rate = 2000000;
    enc->gop_size = (int)(2 * av_q2d(fps));
    if (enc->gop_size < 1) enc->gop_size = 60;
    av_dict_set(&enc_opts, "preset", "veryfast", 0);
    av_dict_set(&enc_opts, "crf", "23", 0);
    ret = avcodec_open2(enc, codec, &enc_opts);
    if (ret < 0) { describe_error(err, errlen, "open encoder", ret); goto done; }

    ret = avformat_alloc_output_context2(&out, NULL, "mp4", opath);
    if (ret < 0) { describe_error(err, errlen, "output context", ret); goto done; }
    vst = avformat_new_stream(out, NULL);
    if (vst == NULL) { ret = AVERROR(ENOMEM); goto done; }
    ret = avcodec_parameters_from_context(vst->codecpar, enc);
    if (ret < 0) { describe_error(err, errlen, "video stream", ret); goto done; }
    vst->time_base = enc->time_base;
    if (top.audio_index >= 0) {
        AVStream *asrc = top.fmt->streams[top.audio_index];
        ast = avformat_new_stream(out, NULL);
        if (ast == NULL) { ret = AVERROR(ENOMEM); goto done; }
        ret = avcodec_parameters_copy(ast->codecpar, asrc->codecpar);
        if (ret < 0) { describe_error(err, errlen, "audio stream", ret); goto done; }
        ast->time_base = asrc->time_base;
    }
    ret = avio_open(&out->pb, opath, AVIO_FLAG_WRITE);
    if (ret < 0) { describe_error(err, errlen, "open output", ret); goto done; }
    out->avoid_negative_ts = AVFMT_AVOID_NEG_TS_MAKE_ZERO;
    av_dict_set(&mux_opts, "movflags", "+faststart", 0);
    ret = avformat_write_header(out, &mux_opts);
    if (ret < 0) { describe_error(err, errlen, "write header", ret); goto done; }
    wrote_header = 1;

    ft = av_frame_alloc();
    fb = av_frame_alloc();
    frame = av_frame_alloc();
    pkt = av_packet_alloc();
    if (ft == NULL || fb == NULL || frame == NULL || pkt == NULL) {
        describe_error(err, errlen, "frame alloc", AVERROR(ENOMEM));
        ret = AVERROR(ENOMEM);
        goto done;
    }
    frame->format = AV_PIX_FMT_YUV420P;
    frame->width = w;
    frame->height = h * 2;
    ret = av_frame_get_buffer(frame, 32);
    if (ret < 0) { describe_error(err, errlen, "frame buffer", ret); goto done; }

    for (;;) {
        int got_top = stack_read_frame(&top, ft, 1);
        int got_bottom = stack_read_frame(&bottom, fb, 0);
        if (!got_top && !got_bottom) break;
        AVFrame *pt = got_top ? stack_yuv420(ft, &sws_t) : NULL;
        AVFrame *pb = got_bottom ? stack_yuv420(fb, &sws_b) : NULL;
        if ((got_top && pt == NULL) || (got_bottom && pb == NULL)) {
            describe_error(err, errlen, "pixel format conversion failed", AVERROR(EINVAL));
            ret = AVERROR(EINVAL);
            goto done;
        }
        av_frame_make_writable(frame);
        stack_planes(frame, pt, pb, w, h);
        frame->pts = pts++;
        ret = avcodec_send_frame(enc, frame);
        if (ret < 0) { describe_error(err, errlen, "encode frame", ret); goto done; }
        while ((ret = avcodec_receive_packet(enc, pkt)) == 0) {
            pkt->stream_index = vst->index;
            av_packet_rescale_ts(pkt, enc->time_base, vst->time_base);
            ret = av_interleaved_write_frame(out, pkt);
            if (ret < 0) { describe_error(err, errlen, "write video", ret); goto done; }
            av_packet_unref(pkt);
        }
        if (pt != NULL && pt != ft) av_frame_free(&pt);
        if (pb != NULL && pb != fb) av_frame_free(&pb);
    }
    stack_flush(enc, out, vst, pkt);

    if (ast != NULL) {
        AVStream *asrc = top.fmt->streams[top.audio_index];
        for (int i = 0; i < top.audio_count; i++) {
            AVPacket *ap = top.audio[i];
            ap->stream_index = ast->index;
            av_packet_rescale_ts(ap, asrc->time_base, ast->time_base);
            ret = av_interleaved_write_frame(out, ap);
            if (ret < 0) { describe_error(err, errlen, "write audio", ret); goto done; }
            av_packet_unref(ap);
        }
    }

    ret = av_write_trailer(out);
    if (ret < 0) describe_error(err, errlen, "write trailer", ret);

done:
    if (wrote_header && out != NULL) {
        /* av_write_trailer was called on success; nothing extra to close. */
    }
    for (int i = 0; i < top.audio_count; i++) {
        av_packet_free(&top.audio[i]);
    }
    av_free(top.audio);
    av_frame_free(&ft);
    av_frame_free(&fb);
    av_frame_free(&frame);
    av_packet_free(&pkt);
    if (sws_t != NULL) sws_freeContext(sws_t);
    if (sws_b != NULL) sws_freeContext(sws_b);
    if (enc != NULL) avcodec_free_context(&enc);
    if (top.dec != NULL) avcodec_free_context(&top.dec);
    if (bottom.dec != NULL) avcodec_free_context(&bottom.dec);
    if (top.fmt != NULL) avformat_close_input(&top.fmt);
    if (bottom.fmt != NULL) avformat_close_input(&bottom.fmt);
    if (out != NULL) {
        if (out->pb != NULL && !(out->oformat->flags & AVFMT_NOFILE)) avio_closep(&out->pb);
        avformat_free_context(out);
    }
    av_dict_free(&mux_opts);
    av_dict_free(&enc_opts);
    return ret;
}

JNIEXPORT void JNICALL
Java_com_pbb_live_RecordService_nativeStack(
    JNIEnv *env, jclass cls, jstring jtop, jstring jbottom, jstring joutput) {
    (void)cls;
    const char *top = (*env)->GetStringUTFChars(env, jtop, NULL);
    const char *bottom = (*env)->GetStringUTFChars(env, jbottom, NULL);
    const char *out = (*env)->GetStringUTFChars(env, joutput, NULL);
    char err[512];
    int result = pbb_stack(top, bottom, out, err, sizeof(err));
    if (result < 0) {
        jclass exc = (*env)->FindClass(env, "java/lang/RuntimeException");
        if (exc != NULL) (*env)->ThrowNew(env, exc, err);
    }
    if (top) (*env)->ReleaseStringUTFChars(env, jtop, top);
    if (bottom) (*env)->ReleaseStringUTFChars(env, jbottom, bottom);
    if (out) (*env)->ReleaseStringUTFChars(env, joutput, out);
}
