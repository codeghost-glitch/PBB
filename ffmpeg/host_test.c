/* Host-side check of pbb_remux against real captured DASH segments. */
#include <stdio.h>

int pbb_remux(const char *vpath, const char *apath, const char *opath,
              const char *key, char *err, size_t errlen);

int main(int argc, char **argv) {
    if (argc != 5) {
        fprintf(stderr, "usage: %s video.fmp4 audio.fmp4 out.mp4 keyhex\n", argv[0]);
        return 2;
    }
    char err[512];
    int ret = pbb_remux(argv[1], argv[2], argv[3], argv[4], err, sizeof(err));
    if (ret < 0) {
        fprintf(stderr, "pbb_remux failed (%d): %s\n", ret, err);
        return 1;
    }
    printf("pbb_remux ok\n");
    return 0;
}
