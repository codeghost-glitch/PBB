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
        theStitchGroupStacksOnceAndAbortsOnFailure();
        theSnapshotScriptMatchesTheApp();
        siteServersAreAccepted();
        theMpdUsesOneAbsoluteSegmentBase();
        cdnPathKeepsItsEscapedLetter();
        theClearKeyHeaderWins();
        aSite503RetriesOnTheCdn();
        theServiceMarksTheStitchGroupAtStart();
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

    private static void siteServersAreAccepted() throws Exception {
        String source = sourceOf("android/java/com/pbb/live/MainActivity.java");
        check("the site routes and both mirrors are accepted", true,
            source.contains("\"s1\", \"pbbtop\", \"s2\", \"s3\", \"s4\", \"twitch\", \"pbb3\", \"mirror\""));
        check("site routes use the site MPD endpoint", true,
            source.contains("\"pbbtop\".equals(server) || \"s4\".equals(server)"));
    }

    private static void theMpdUsesOneAbsoluteSegmentBase() throws Exception {
        String source = sourceOf("android/java/com/pbb/live/MainActivity.java");
        check("a root-relative folder is anchored to the site", true,
            source.contains("base.startsWith(\"/\") ? SITE2 + base : base"));
        check("templates lose any absolute or root-relative folder", true,
            source.contains("(media|initialization)=")
            && source.contains("replaceAll("));
        check("every baked BaseURL is dropped", true,
            source.contains("<BaseURL>[^<]*</BaseURL>"));
        check("signed segment queries survive the rewrite", true,
            source.contains("// Strip only a leading folder")
            && source.contains("(media|initialization)=")
            && source.contains("String base = manifestBase(xml);"));
        String cdn = "https://absp-live.akamaized.net/dash/live/2099522/";
        check("the signed mirror URLs keep their query", true,
            Http.normalizeCdnUrl(cdn + "pbbcollab30x/init.mp4?token=1")
                .equals(cdn + "pbbcollab30x/init.mp4?token=1"));
        check("the CCTV mirror has its own signed MPDs", true,
            source.contains("MIRROR_MPD = {")
            && source.contains("MIRROR + CAM_MIRROR[0] + \"/manifest.mpd\""));
    }

    private static void cdnPathKeepsItsEscapedLetter() {
        String base = "https://absp-live.akamaized.net/dash/live/2099522/";
        check("the literal CDN folder is escaped again", base + "%70bbcollab30x/init.mp4",
            Http.normalizeCdnUrl(base + "pbbcollab30x/init.mp4"));
        check("an already escaped CDN folder stays put", base + "%70bbcollab30x/init.mp4",
            Http.normalizeCdnUrl(base + "%70bbcollab30x/init.mp4"));
        String signed = base + "pbbcollab30x/init.mp4?expires=1~acl=/*~hmac=abc";
        check("a signed mirror URL is left alone", signed, Http.normalizeCdnUrl(signed));
    }

    private static void theClearKeyHeaderWins() throws Exception {
        String javaSource = sourceOf("android/java/com/pbb/live/MainActivity.java");
        String httpSource = sourceOf("android/java/com/pbb/live/Http.java");
        check("the MPD header is read with the body", true,
            httpSource.contains("X-PBB-ClearKey"));
        check("the published kid, not the old MPD KID, is compared", true,
            javaSource.contains("publishedClearKey(i, srv)")
            && !javaSource.contains("manifestKid(url)"));
        check("a published pair overrides the bundled one", true,
            javaSource.contains("liveKeys == null ? bundled.getJSONObject(i).getString(\"kid\") : liveKeys[0]"));
        check("the MPD KID is rewritten to the published key", true,
            javaSource.contains("cenc:default_KID=\\\"")
            && javaSource.contains("Http.psshForKid(cleanKid)"));
        check("site segments get a cache-safe query", true,
            javaSource.contains("$2?_pbb=1"));
    }

    private static void aSite503RetriesOnTheCdn() throws Exception {
        String source = sourceOf("android/java/com/pbb/live/MainActivity.java");
        check("the site proxy retries a 503 on the CDN", true,
            source.contains("if (status == 503 && hops < 1)")
            && source.contains("String cdn = cdnSegment(url);"));
        check("site segment paths map to the escaped CDN folder", true,
            source.contains("String root = SITE2 + \"/chat/clearmpd/\"")
            && source.contains("return cdn + name;"));
    }

    private static void theServiceMarksTheStitchGroupAtStart() throws Exception {
        String source = sourceOf("android/java/com/pbb/live/MainActivity.java");
        String service = sourceOf("android/java/com/pbb/live/RecordService.java");
        int start = source.indexOf("private void startRecordService()");
        int end = source.indexOf("private String makeDisplayName", start);
        String body = source.substring(start, end);
        check("the group is marked when all three starts are sent", true,
            body.contains("RecordService.markStackGroup(0, 1, 2);")
            && body.contains("indices.contains(Integer.valueOf(2))"));
        check("the stack start reports its status", true,
            service.contains("Saving stacked video"));
    }

    /**
     * The join runs only when all three feeds saved. A partial group waits, a
     * failed feed abandons it, and a running join is never started twice.
     */
    private static void theStitchGroupStacksOnceAndAbortsOnFailure() {
        boolean[] all = {true, true, true};
        boolean[] partial = {true, true, false};
        check("the last feed joins the group", RecordService.STACK_JOIN,
            RecordService.stackStep(0, false, false, true, all));
        check("a partial group waits", RecordService.STACK_WAIT,
            RecordService.stackStep(0, false, false, true, partial));
        check("a failed feed abandons the group", RecordService.STACK_ABORT,
            RecordService.stackStep(0, false, false, false, all));
        check("a running join is not started twice", RecordService.STACK_WAIT,
            RecordService.stackStep(0, true, false, true, all));
        check("no group means nothing to join", RecordService.STACK_WAIT,
            RecordService.stackStep(-1, false, false, true, all));
        check("an abandoned group never rejoins", RecordService.STACK_WAIT,
            RecordService.stackStep(0, false, true, true, all));
    }

    /**
     * keys/session.py writes the checked-in snapshot and carries its own copy of
     * the payload format, so both are run on one seed here. If the site changes
     * the shape, this fails instead of shipping a stale snapshot.
     */
    private static void theSnapshotScriptMatchesTheApp() throws Exception {
        byte[] seed = new byte[13];
        for (int i = 0; i < seed.length; i++) {
            seed[i] = (byte) (i * 7 + 1);
        }
        StringBuilder hex = new StringBuilder();
        for (int i = 0; i < 16; i++) {
            hex.append(String.format(java.util.Locale.US, "%02x", i * 11 + 3));
        }
        String key = hex.toString();
        for (int mode = 0; mode < 6; mode++) {
            long millis = 123456789L + mode;
            String app = Http.sessionPlain(seed, key, mode, millis);
            String script = python("-c",
                "import sys;sys.path.insert(0,'keys');import session;"
                + "print(session.plain(bytes([i*7+1 for i in range(13)]),'" + key + "',"
                + mode + "," + millis + "))");
            check("the snapshot script builds the same payload", app, script);
        }
    }

    private static String python(String... args) throws Exception {
        List<String> command = new ArrayList<String>();
        command.add("python3");
        for (String arg : args) {
            command.add(arg);
        }
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String out;
        try {
            out = new String(Http.readAll(process.getInputStream()), "UTF-8");
        } finally {
            process.getInputStream().close();
        }
        process.waitFor();
        return out.trim();
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
