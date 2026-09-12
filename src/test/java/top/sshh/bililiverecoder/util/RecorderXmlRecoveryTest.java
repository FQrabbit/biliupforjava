package top.sshh.bililiverecoder.util;

import org.junit.jupiter.api.Test;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class RecorderXmlRecoveryTest {
    private static final String D = "<d p=\"1,1,25,16777215,1000,0,123,0\">hello</d>";

    @Test void legalInputIsByteForByteUnchangedIncludingBom() throws Exception {
        String original = "\uFEFF<i>\n" + D + "\n</i>";
        var result = run(original);
        try {
            assertTrue(result.before().valid());
            assertFalse(result.changed());
            assertEquals(original, Files.readString(result.tempFile()));
        } finally { Files.deleteIfExists(result.tempFile()); }
    }

    @Test void repairsBareAmpersandAndRetainsOtherRecords() throws Exception {
        var result = run("<i>\n" + D.replace("hello", "a & b") + "\n" + D + "\n</i>");
        try {
            assertTrue(result.after().valid());
            assertEquals(1, count(result, "recovered", "d"));
            assertEquals(1, count(result, "kept", "d"));
            assertTrue(Files.readString(result.tempFile()).contains("a &amp; b"));
        } finally { Files.deleteIfExists(result.tempFile()); }
    }

    @Test void restoresTruncatedTextOnlyFromMatchingRaw() throws Exception {
        String event = "<d p=\"1,1,25,16777215,1000,0,123,0\" raw=\"[[],&quot;hello world&quot;,[123]]\">hello";
        var result = run("<i>\n" + event);
        try {
            assertTrue(result.after().valid());
            assertEquals(1, count(result, "recovered", "d"));
            assertTrue(Files.readString(result.tempFile()).contains(">hello world</d>"));
        } finally { Files.deleteIfExists(result.tempFile()); }
    }

    @Test void doesNotInventPaidFieldsAndContinuesAfterBrokenRecord() throws Exception {
        var result = run("<i>\n<gift ts=\"1\" uid=\"123\" giftname=\"x\" giftcount=\"\" raw=\"broken\n" + D + "\n</i>");
        try {
            assertTrue(result.after().valid());
            assertEquals(1, count(result, "discarded", "gift"));
            assertEquals(1, result.counts().danmu());
            assertEquals(true, result.report().get("lossy"));
        } finally { Files.deleteIfExists(result.tempFile()); }
    }

    @Test void incompleteRawOrConflictingTextIsDiscarded() throws Exception {
        var result = run("<i>\n<sc ts=\"1\" uid=\"123\" price=\"30\" time=\"60\" raw=\"{&quot;message&quot;:&quot;abc&quot;,&quot;price&quot;:30}\">xyz\n" + D + "\n</i>");
        try {
            assertTrue(result.after().valid());
            assertEquals(1, count(result, "discarded", "sc"));
        } finally { Files.deleteIfExists(result.tempFile()); }
    }

    @Test void commentsDoNotBecomeEventsAndCdataAmpersandsStayLiteral() throws Exception {
        String input = "<i>\n<!--\n" + D + "\n-->\n" + D.replace("hello", "<![CDATA[a & b]]>") + "\n" + D.replace("hello", "bad\u0001char");
        var result = run(input);
        try {
            assertTrue(result.after().valid());
            assertEquals(2, result.counts().danmu());
            assertTrue(Files.readString(result.tempFile()).contains("<![CDATA[a & b]]>"));
        } finally { Files.deleteIfExists(result.tempFile()); }
    }

    @Test void noRecoverableEventsIsFailure() throws Exception {
        var result = run("<i>\n<d p=\"broken");
        try { assertFalse(result.after().valid()); }
        finally { Files.deleteIfExists(result.tempFile()); }
    }

    @Test void rejectsInvalidUtf8RatherThanReplacingCharacters() {
        assertThrows(java.io.IOException.class, () -> XmlRepairTool.streamRepair(new ByteArrayInputStream(
                new byte[]{'<','i','>','\n','<','d',' ',(byte)0xff})));
    }

    @Test void realSampleRetainsAllEventsWhenRootClosingTagIsLost() throws Exception {
        String file = System.getProperty("xml.repair.sample");
        assumeTrue(file != null);
        byte[] bytes = Files.readAllBytes(Path.of(file));
        String input = new String(bytes, StandardCharsets.UTF_8);
        var unchanged = run(input);
        try { assertFalse(unchanged.changed()); assertArrayEquals(bytes, Files.readAllBytes(unchanged.tempFile())); }
        finally { Files.deleteIfExists(unchanged.tempFile()); }
        int end = input.lastIndexOf("</i>");
        var result = run(input.substring(0, end));
        try {
            assertTrue(result.after().valid(), result.after().message());
            assertEquals(new XmlRepairTool.Counts(769, 342, 1, 9), result.counts());
            assertEquals(0, count(result, "discarded", "gift"));
            assertEquals(false, result.report().get("headerRebuilt"));
            String output = Files.readString(result.tempFile());
            for (String line : input.split("\\R")) {
                String event = line.strip();
                if (event.matches("<(d|gift|sc|guard)\\s.*")) assertTrue(output.contains(event));
            }
        } finally { Files.deleteIfExists(result.tempFile()); }
    }

    private static XmlRepairTool.StreamRepairResult run(String input) throws Exception {
        return XmlRepairTool.streamRepair(new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)));
    }

    @Test void restoresPaidTerminatorOnlyWithCompleteMatchingRaw() throws Exception {
        var result = run("<i>\n<gift ts=\"1\" uid=\"123\" giftname=\"x\" giftcount=\"1\" raw=\"{&quot;uid&quot;:123,&quot;price&quot;:100}\">\n" + D);
        try {
            assertTrue(result.after().valid());
            assertEquals(1, count(result, "recovered", "gift"));
        } finally { Files.deleteIfExists(result.tempFile()); }
    }

    @Test void incompleteRawIsNeverUsedToRestorePaidRecord() throws Exception {
        var result = run("<i>\n<gift ts=\"1\" uid=\"123\" giftname=\"x\" giftcount=\"1\" raw=\"{&quot;uid&quot;:123\">\n" + D);
        try {
            assertTrue(result.after().valid());
            assertEquals(1, count(result, "discarded", "gift"));
        } finally { Files.deleteIfExists(result.tempFile()); }
    }

    @Test void headerDamageIsReportedInsteadOfSilentlyLosingMetadata() throws Exception {
        var result = run("<i>\n<chatid>123</chatid>\n<broken\n" + D);
        try {
            assertTrue(result.after().valid());
            assertEquals(true, result.report().get("headerRebuilt"));
            assertEquals(true, result.report().get("lossy"));
            assertTrue(Files.readString(result.tempFile()).contains("<chatid>123</chatid>"));
        } finally { Files.deleteIfExists(result.tempFile()); }
    }

    @Test void unsupportedEntitiesDoNotReadExternalFiles() {
        assertThrows(java.io.IOException.class, () -> run("<!DOCTYPE i [<!ENTITY x SYSTEM 'file:///never-read'>]>\n<i>\n" + D.replace("hello", "&x;") + "\n</i>"));
    }

    @Test void realSampleIsolatesBrokenGiftAndRestoresTruncatedDanmuAndSc() throws Exception {
        String file = System.getProperty("xml.repair.sample");
        assumeTrue(file != null);
        byte[] original = Files.readAllBytes(Path.of(file));
        String[] lines = new String(original, StandardCharsets.UTF_8).split("\\R");
        boolean d = false, sc = false, gift = false;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].stripLeading();
            if (!d && line.startsWith("<d ")) {
                lines[i] = line.substring(0, line.indexOf('>') + 1);
                d = true;
            } else if (!sc && line.startsWith("<sc ")) {
                lines[i] = line.substring(0, line.indexOf('>') + 1);
                sc = true;
            } else if (!gift && line.startsWith("<gift ")) {
                lines[i] = "<gift ts=\"120.533\" uid=\"298726976\" giftcount=\"";
                gift = true;
            }
        }
        assertTrue(d && sc && gift);
        var result = run(String.join("\n", lines));
        try {
            assertTrue(result.after().valid(), result.after().message());
            assertEquals(new XmlRepairTool.Counts(769, 341, 1, 9), result.counts());
            assertEquals(1, count(result, "recovered", "d"));
            assertEquals(1, count(result, "recovered", "sc"));
            assertEquals(1, count(result, "discarded", "gift"));
            assertArrayEquals(original, Files.readAllBytes(Path.of(file)));
        } finally { Files.deleteIfExists(result.tempFile()); }
    }
    private static int count(XmlRepairTool.StreamRepairResult result, String group, String type) {
        return ((Number)((Map<?, ?>)result.report().get(group)).get(type)).intValue();
    }
}
