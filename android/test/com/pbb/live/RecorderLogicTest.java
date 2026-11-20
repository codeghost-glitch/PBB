package com.pbb.live;

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
        manifestCarriesTheKid();
        segmentUrlPadsTheNumber();
        segmentUrlHandlesBareNumber();
        pickVideoFollowsThePageRules();
        pickAudioFollowsTheLiveEdge();
        liveOffsetFollowsMpdTiming();
        partNameKeepsTheExtension();
        emptyGapPartRetriesButIsBounded();
        liveApiIsUsed();
        serviceStopsOnlyWhenIdle();
        stitchKeepsBothPendingStarts();
        stopBreaksSocketReads();
        cookieStaysOnTheSite();
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

    private static void liveApiIsUsed() throws Exception {
        String source = new String(java.nio.file.Files.readAllBytes(
            java.nio.file.Paths.get("android/java/com/pbb/live/MainActivity.java")),
            java.nio.charset.StandardCharsets.UTF_8);
        check("the pbbcollab.live site supplies the streams", true,
            source.contains("https://www.pbbcollab.live")
                && source.contains("/api/streams/session"));
    }

    private static void serviceStopsOnlyWhenIdle() {
        check("an idle service stops", true, RecordService.shouldStopService(0, false));
        check("another recording keeps it up", false, RecordService.shouldStopService(1, false));
        check("a join keeps it up", false, RecordService.shouldStopService(0, true));
    }

    private static String sourceOf(String path) throws Exception {
        return new String(java.nio.file.Files.readAllBytes(
            java.nio.file.Paths.get(path)), java.nio.charset.StandardCharsets.UTF_8);
    }

    private static void stitchKeepsBothPendingStarts() throws Exception {
        String java = sourceOf("android/java/com/pbb/live/MainActivity.java");
        String page = sourceOf("android/assets/app.js");
        int start = java.indexOf("private void requestRecording");
        int end = java.indexOf("private void startRecordService", start);
        String beforePermission = java.substring(start, end);
        check("both pending starts survive the permission dialog", true,
            beforePermission.contains("pendingRecordIndices.contains")
            && !beforePermission.contains("pendingRecordIndices.clear()"));
        check("stitch still asks for both cameras", true,
            page.contains("for (const index of STITCH_FEEDS) {")
            && page.contains("Pbb.startRecording(index, players[index].stream.name);"));
    }

    private static void stopBreaksSocketReads() throws Exception {
        String source = sourceOf("android/java/com/pbb/live/RecordService.java");
        check("a stop disconnects in-flight reads", true,
            source.contains("disconnectActive(session);")
            && source.contains("session.connections.remove(conn);")
            && source.contains("fetch(vUrl, session)"));
    }

    private static void cookieStaysOnTheSite() throws Exception {
        String source = sourceOf("android/java/com/pbb/live/MainActivity.java");
        int at = source.indexOf("conn.setRequestProperty(\"Cookie\", sessionCookie);");
        int site = source.lastIndexOf("url.startsWith(SITE + \"/\")", at);
        check("the session cookie is only sent to the site", true,
            at > site && source.indexOf("conn.setRequestProperty(\"Cookie\", sessionCookie);", site) == at);
    }

    private static void sevenServersAreAccepted() throws Exception {
        String source = sourceOf("android/java/com/pbb/live/MainActivity.java");
        check("all seven server ids are accepted", true,
            source.contains("n >= '1' && n <= '7'"));
        check("site servers map to the four site routes", true,
            source.contains("server.charAt(1) - '3'"));
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
        // sd is the smallest one that is at least 480 lines, like on the page.
        check("sd pick", "v720-$Number$.m4s",
            RecordService.pickVideo(tracks, "sd", 5).media);
        // Unknown values migrate to hd, which picks the tallest variant.
        check("unknown quality falls back to hd", "v1080-$Number$.m4s",
            RecordService.pickVideo(tracks, "4k", 5).media);
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
