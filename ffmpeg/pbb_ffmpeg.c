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
 * Stack three finished recordings into one tall MP4. The published files
 * are already decrypted by pbb_remux, so no key is needed here. All feeds
 * are captured at the same quality, so their frames match 1:1; one frame
 * from each is joined into a single H.264 frame.
 * ------------------------------------------------------------------ */

typedef struct {
    AVFormatContext *fmt;
    AVCodecContext *dec;
    int video_index;
    int audio_index;
    AVPacket *pkt;
    int eof;
    /* Where the kept audio goes: the output mux and the streams it joins.
     * Writing each packet as it arrives keeps the track interleaved with the
     * picture, and keeps a long recording out of memory. */
    AVFormatContext *out;
    AVStream *out_audio;
    AVStream *src_audio;
    int audio_failed;
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
                   && in->pkt->stream_index == in->audio_index
                   && in->out_audio != NULL && in->src_audio != NULL) {
            in->pkt->stream_index = in->out_audio->index;
            av_packet_rescale_ts(in->pkt, in->src_audio->time_base,
                in->out_audio->time_base);
            if (av_interleaved_write_frame(in->out, in->pkt) < 0) {
                in->audio_failed = 1;
            }
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

static void stack_planes(AVFrame *dst, const AVFrame *first, const AVFrame *second,
                         const AVFrame *third, int w, int h) {
    /* Each source contributes its full height (h) to the 3h output; the
     * chroma planes contribute h/2 rows each. */
    const AVFrame *frames[3] = { first, second, third };
    int rows_y = h;
    int rows_c = h / 2;
    int cw = w / 2;
    for (int i = 0; i < 3; i++) {
        const AVFrame *src = frames[i];
        stack_rows(dst->data[0] + (size_t)(i * rows_y) * dst->linesize[0],
            dst->linesize[0], src ? src->data[0] : NULL,
            src ? src->linesize[0] : 0, w, rows_y);
        stack_rows(dst->data[1] + (size_t)(i * rows_c) * dst->linesize[1],
            dst->linesize[1], src ? src->data[1] : NULL,
            src ? src->linesize[1] : 0, cw, rows_c);
        stack_rows(dst->data[2] + (size_t)(i * rows_c) * dst->linesize[2],
            dst->linesize[2], src ? src->data[2] : NULL,
            src ? src->linesize[2] : 0, cw, rows_c);
    }
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

int pbb_stack(const char *first_path, const char *second_path,
              const char *third_path, const char *opath, char *err, size_t errlen) {
    StackInput first, second, third;
    AVFormatContext *out = NULL;
    AVCodecContext *enc = NULL;
    AVFrame *f1 = NULL, *f2 = NULL, *f3 = NULL, *frame = NULL;
    AVPacket *pkt = NULL;
    struct SwsContext *sws1 = NULL, *sws2 = NULL, *sws3 = NULL;
    AVStream *vst = NULL, *ast = NULL;
    AVStream *asrc = NULL;
    AVDictionary *mux_opts = NULL, *enc_opts = NULL;
    int w, h, ret = AVERROR(EINVAL), wrote_header = 0;
    int64_t pts = 0;

    if (err != NULL && errlen > 0) err[0] = '\0';
    av_log_set_level(AV_LOG_ERROR);
    if (first_path == NULL || second_path == NULL || third_path == NULL || opath == NULL) {
        describe_error(err, errlen, "null argument", AVERROR(EINVAL));
        return AVERROR(EINVAL);
    }

    ret = stack_open(&first, first_path);
    if (ret < 0) { describe_error(err, errlen, "open all-access recording", ret); goto done; }
    ret = stack_open(&second, second_path);
    if (ret < 0) { describe_error(err, errlen, "open dining recording", ret); goto done; }
    ret = stack_open(&third, third_path);
    if (ret < 0) { describe_error(err, errlen, "open den recording", ret); goto done; }

    AVStream *vsrc = first.fmt->streams[first.video_index];
    AVStream *vs2 = second.fmt->streams[second.video_index];
    AVStream *vs3 = third.fmt->streams[third.video_index];
    w = vsrc->codecpar->width;
    h = vsrc->codecpar->height;
    if (w <= 0 || h <= 0 || w % 2 != 0 || h % 2 != 0
        || vs2->codecpar->width != w || vs2->codecpar->height != h
        || vs3->codecpar->width != w || vs3->codecpar->height != h) {
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
    enc->height = h * 3;
    enc->pix_fmt = AV_PIX_FMT_YUV420P;
    AVRational fps = vsrc->avg_frame_rate;
    if (fps.num <= 0 || fps.den <= 0) fps = (AVRational){ 30, 1 };
    enc->framerate = fps;
    enc->time_base = av_inv_q(fps);
    enc->bit_rate = 3000000;
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
    if (first.audio_index >= 0) {
        asrc = first.fmt->streams[first.audio_index];
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
    first.out = out;
    first.out_audio = ast;
    first.src_audio = asrc;

    f1 = av_frame_alloc();
    f2 = av_frame_alloc();
    f3 = av_frame_alloc();
    frame = av_frame_alloc();
    pkt = av_packet_alloc();
    if (f1 == NULL || f2 == NULL || f3 == NULL || frame == NULL || pkt == NULL) {
        describe_error(err, errlen, "frame alloc", AVERROR(ENOMEM));
        ret = AVERROR(ENOMEM);
        goto done;
    }
    frame->format = AV_PIX_FMT_YUV420P;
    frame->width = w;
    frame->height = h * 3;
    ret = av_frame_get_buffer(frame, 32);
    if (ret < 0) { describe_error(err, errlen, "frame buffer", ret); goto done; }

    for (;;) {
        int got1 = stack_read_frame(&first, f1, 1);
        int got2 = stack_read_frame(&second, f2, 0);
        int got3 = stack_read_frame(&third, f3, 0);
        if (!got1 && !got2 && !got3) break;
        AVFrame *p1 = got1 ? stack_yuv420(f1, &sws1) : NULL;
        AVFrame *p2 = got2 ? stack_yuv420(f2, &sws2) : NULL;
        AVFrame *p3 = got3 ? stack_yuv420(f3, &sws3) : NULL;
        if ((got1 && p1 == NULL) || (got2 && p2 == NULL) || (got3 && p3 == NULL)) {
            describe_error(err, errlen, "pixel format conversion failed", AVERROR(EINVAL));
            ret = AVERROR(EINVAL);
            goto done;
        }
        av_frame_make_writable(frame);
        stack_planes(frame, p1, p2, p3, w, h);
        /* Follow the first feed's own timeline. A plain counter drifts from the
         * audio as soon as a feed drops a segment. */
        if (got1 && (f1->pts != AV_NOPTS_VALUE
                     || f1->best_effort_timestamp != AV_NOPTS_VALUE)) {
            int64_t src = f1->pts != AV_NOPTS_VALUE
                ? f1->pts : f1->best_effort_timestamp;
            frame->pts = av_rescale_q(src, vsrc->time_base, enc->time_base);
        } else {
            frame->pts = pts;
        }
        pts = frame->pts + 1;
        ret = avcodec_send_frame(enc, frame);
        if (ret < 0) { describe_error(err, errlen, "encode frame", ret); goto done; }
        while ((ret = avcodec_receive_packet(enc, pkt)) == 0) {
            pkt->stream_index = vst->index;
            av_packet_rescale_ts(pkt, enc->time_base, vst->time_base);
            ret = av_interleaved_write_frame(out, pkt);
            if (ret < 0) { describe_error(err, errlen, "write video", ret); goto done; }
            av_packet_unref(pkt);
        }
        if (p1 != NULL && p1 != f1) av_frame_free(&p1);
        if (p2 != NULL && p2 != f2) av_frame_free(&p2);
        if (p3 != NULL && p3 != f3) av_frame_free(&p3);
    }
    stack_flush(enc, out, vst, pkt);
    if (first.audio_failed) {
        describe_error(err, errlen, "write audio", AVERROR(EIO));
        ret = AVERROR(EIO);
        goto done;
    }

    ret = av_write_trailer(out);
    if (ret < 0) describe_error(err, errlen, "write trailer", ret);

done:
    if (wrote_header && out != NULL) {
        /* av_write_trailer was called on success; nothing extra to close. */
    }
    av_frame_free(&f1);
    av_frame_free(&f2);
    av_frame_free(&f3);
    av_frame_free(&frame);
    av_packet_free(&pkt);
    if (sws1 != NULL) sws_freeContext(sws1);
    if (sws2 != NULL) sws_freeContext(sws2);
    if (sws3 != NULL) sws_freeContext(sws3);
    if (enc != NULL) avcodec_free_context(&enc);
    if (first.dec != NULL) avcodec_free_context(&first.dec);
    if (second.dec != NULL) avcodec_free_context(&second.dec);
    if (third.dec != NULL) avcodec_free_context(&third.dec);
    if (first.fmt != NULL) avformat_close_input(&first.fmt);
    if (second.fmt != NULL) avformat_close_input(&second.fmt);
    if (third.fmt != NULL) avformat_close_input(&third.fmt);
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
    JNIEnv *env, jclass cls, jstring jfirst, jstring jsecond, jstring jthird,
    jstring joutput) {
    (void)cls;
    const char *first = (*env)->GetStringUTFChars(env, jfirst, NULL);
    const char *second = (*env)->GetStringUTFChars(env, jsecond, NULL);
    const char *third = (*env)->GetStringUTFChars(env, jthird, NULL);
    const char *out = (*env)->GetStringUTFChars(env, joutput, NULL);
    char err[512];
    int result = pbb_stack(first, second, third, out, err, sizeof(err));
    if (result < 0) {
        jclass exc = (*env)->FindClass(env, "java/lang/RuntimeException");
        if (exc != NULL) (*env)->ThrowNew(env, exc, err);
    }
    if (first) (*env)->ReleaseStringUTFChars(env, jfirst, first);
    if (second) (*env)->ReleaseStringUTFChars(env, jsecond, second);
    if (third) (*env)->ReleaseStringUTFChars(env, jthird, third);
    if (out) (*env)->ReleaseStringUTFChars(env, joutput, out);
}
