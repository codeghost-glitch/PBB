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

    public static void main(String[] args) {
        segmentUrlPadsTheNumber();
        segmentUrlHandlesBareNumber();
        pickVideoStaysUnderTheCeilingAndOnTheLiveEdge();
        pickAudioFollowsTheLiveEdge();
        partNameKeepsTheExtension();
        if (failures > 0) {
            System.err.println(failures + " check(s) failed");
            System.exit(1);
        }
        System.out.println("recorder logic checks passed");
    }

    private static void check(String what, Object expected, Object actual) {
        if (expected == null ? actual != null : !expected.equals(actual)) {
            failures++;
            System.err.println(what + ": expected " + expected + ", got " + actual);
        }
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

    private static void pickVideoStaysUnderTheCeilingAndOnTheLiveEdge() {
        List<RecordService.Track> tracks = new ArrayList<RecordService.Track>();
        tracks.add(track(1920, 5, "v1080-$Number$.m4s"));
        tracks.add(track(1280, 5, "v720-$Number$.m4s"));
        tracks.add(track(640, 5, "v360-$Number$.m4s"));
        tracks.add(track(1280, 9, "v720-old-$Number$.m4s"));
        check("hd pick", "v720-$Number$.m4s",
            RecordService.pickVideo(tracks, "hd", 5).media);
        check("sd pick", "v360-$Number$.m4s",
            RecordService.pickVideo(tracks, "sd", 5).media);
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

    private static void partNameKeepsTheExtension() {
        check("part name", "PBB_All_20260101_2.mp4",
            RecordService.partName("PBB_All_20260101.mp4", 2));
    }

    private RecorderLogicTest() {
    }
}
