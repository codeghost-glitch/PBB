package com.pbb.live;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Host-side checks for the recorder's pure parts. Compiled outside the APK and
 * run by build.sh, so a broken segment URL or quality pick fails the build
 * instead of failing on a live broadcast.
 */
public final class RecorderLogicTest {
    private static int failures = 0;

    public static void main(String[] args) throws Exception {
        channelListReadsM3u();
        manifestCarriesTheKid();
        segmentUrlPadsTheNumber();
        segmentUrlHandlesBareNumber();
        pickVideoFollowsThePageRules();
        pickAudioFollowsTheLiveEdge();
        liveOffsetFollowsMpdTiming();
        partNameKeepsTheExtension();
        emptyGapPartRetriesButIsBounded();
        if (failures > 0) {
            System.err.println(failures + " check(s) failed");
            System.exit(1);
        }
        System.out.println("recorder logic checks passed");
    }

    private static void liveOffsetFollowsMpdTiming() {
        String mpd = "<MPD type=\"dynamic\" "
            + "availabilityStartTime=\"2026-11-10T02:59:29.905Z\" "
            + "publishTime=\"2026-11-14T11:56:31.364Z\">"
            + "<Period><SegmentTemplate timescale=\"90000\" duration=\"360360\" "
            + "startNumber=\"1794279564\"/></Period></MPD>";
        check("the live edge comes from the MPD's own timing", 94359L,
            RecordService.liveOffsetFromMpd(mpd));
        check("a manifest without timing falls back to scanning", -1L,
            RecordService.liveOffsetFromMpd("<MPD type=\"dynamic\"></MPD>"));
    }

    private static void channelListReadsM3u() throws Exception {
        String[] names = {"PBB All Access", "PBB Dining Area", "PBB External Den"};
        String m3u = "#EXTM3U\n"
            + "#EXTINF:-1, PBB External Den\n"
            + "#KODIPROP:inputstream.adaptive.license_key="
            + "b750a40736a24e60b5a4f256208b92c5:af07292597f692726dcac2bd297ddf8e\n"
            + "https://absp-live.akamaized.net/dash/live/2099522/den/manifest.mpd\n"
            + "#EXTINF:-1, PBB All Access\n"
            + "##KODIPROP:inputstream.adaptive.license_key=deadbeef:deadbeef\n"
            + "#KODIPROP:inputstream.adaptive.license_key="
            + "8150b74d-1632-4bb4-b2ff-62e3e202fc7a:0c5ed4f9db9d4ee61bbc91b3a51ef8b5\n"
            + "https://absp-live.akamaized.net/dash/live/2099522/all/manifest.mpd\n"
            + "#EXTINF:-1, PBB Dining Area\n"
            + "#KODIPROP:inputstream.adaptive.license_key="
            + "ea6588d030c94ca89925ac0c49974430:3e6de4afee1eff8e14f9506b76ac9ee6\n"
            + "https://absp-live.akamaized.net/dash/live/2099522/dining/manifest.mpd\n";
        JSONArray streams = ChannelList.fromM3u(m3u, names);
        check("the M3U yields three streams", 3, streams.length());
        JSONObject first = streams.getJSONObject(0);
        check("the first row is All Access", names[0], first.getString("name"));
        check("the manifest uses the APK's live CDN",
            "https://abslive.akamaized.net/dash/live/2099522/all/manifest.mpd",
            first.getString("url"));
        check("the kid loses its dashes", "8150b74d16324bb4b2ff62e3e202fc7a", first.getString("kid"));
        check("the key is read", "0c5ed4f9db9d4ee61bbc91b3a51ef8b5", first.getString("key"));
        check("the rows come back in channel order", names[1], streams.getJSONObject(1).getString("name"));
        check("a missing channel rejects the list", null,
            ChannelList.fromM3u(m3u.replace("PBB Dining Area", "Gone"), names));
    }

    private static void check(String what, Object expected, Object actual) {
        if (expected == null ? actual != null : !expected.equals(actual)) {
            failures++;
            System.err.println(what + ": expected " + expected + ", got " + actual);
        }
    }

    private static void manifestCarriesTheKid() {
        check("the kid is read",
            "8150b74d16324bb4b2ff62e3e202fc7a",
            RecordService.manifestKid(
                "<ContentProtection cenc:default_KID=\"8150b74d-1632-4bb4-b2ff-62e3e202fc7a\"/>"));
        check("a manifest without a kid has none", "",
            RecordService.manifestKid("<MPD></MPD>"));
    }

    private static void segmentUrlPadsTheNumber() {
        check("padded segment",
            "video-$Number".replace("$Number", "00042") + ".m4s",
            RecordService.segmentUrl("video-$Number%05d$.m4s", 42));
    }

    private static void segmentUrlHandlesBareNumber() {
        check("bare segment", "audio-7.m4s", RecordService.segmentUrl("audio-$Number$.m4s", 7));
    }

    private static RecordService.Track track(int width, long start, String media) {
        RecordService.Track t = new RecordService.Track();
        t.width = width;
        t.bandwidth = width;
        t.startNumber = start;
        t.seconds = 4;
        t.media = media;
        t.init = "init.mp4";
        return t;
    }

    private static void pickVideoFollowsThePageRules() {
        List<RecordService.Track> tracks = new ArrayList<RecordService.Track>();
        tracks.add(track(1920, 5, "v1080-$Number$.m4s"));
        tracks.add(track(1280, 5, "v720-$Number$.m4s"));
        tracks.add(track(640, 5, "v360-$Number$.m4s"));
        tracks.add(track(1280, 9, "v720-old-$Number$.m4s"));
        // hd is the tallest variant the feed publishes, like on the page.
        check("hd pick", "v1080-$Number$.m4s",
            RecordService.pickVideo(tracks, "hd", 5).media);
        // sd is the shortest one.
        check("sd pick", "v360-$Number$.m4s",
            RecordService.pickVideo(tracks, "sd", 5).media);
        // auto cannot adapt, so it takes the tallest variant up to 1280 wide.
        check("auto pick", "v720-$Number$.m4s",
            RecordService.pickVideo(tracks, "auto", 5).media);
        check("missing edge falls back", "v1080-$Number$.m4s",
            RecordService.pickVideo(tracks, "hd", 99).media);
    }

    private static void pickAudioFollowsTheLiveEdge() {
        List<RecordService.Track> tracks = new ArrayList<RecordService.Track>();
        tracks.add(track(0, 5, "a-old-$Number$.m4s"));
        tracks.add(track(0, 9, "a-new-$Number$.m4s"));
        check("audio on the edge", "a-new-$Number$.m4s",
            RecordService.pickAudio(tracks, 9).media);
        check("audio falls back", "a-old-$Number$.m4s",
            RecordService.pickAudio(tracks, 99).media);
    }

    private static void emptyGapPartRetriesButIsBounded() {
        check("an empty gap part re-anchors", true,
            RecordService.retryEmptyPart(true, true, 0));
        check("retries stay bounded", false,
            RecordService.retryEmptyPart(true, true, RecordService.MAX_EMPTY_RETRIES));
        check("a clean stop never retries", false,
            RecordService.retryEmptyPart(false, true, 0));
        check("a stopped session never retries", false,
            RecordService.retryEmptyPart(true, false, 0));
    }

    private static void partNameKeepsTheExtension() {
        check("part name", "PBB_All_20260101_2.mp4",
            RecordService.partName("PBB_All_20260101.mp4", 2));
    }

    private RecorderLogicTest() {
    }
}
