#include <jni.h>
#include <stdio.h>
#include <stdint.h>
#include <string.h>

#include "libavformat/avformat.h"
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
