package dev.mokouliszt.githubpatches.patch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Parsing only; no git needed. */
class PatchParserTest {

    /** [index]: git writes an index line; a hand-written diff usually has none. */
    private fun diff(hunkHeader: ByteArray, body: String = " a\n-b\n+c\n d\n", index: Boolean = true): ByteArray =
        ("diff --git a/README.ja.md b/README.ja.md\n" + (if (index) "index 324633b..356bed5 100644\n" else "") +
            "--- a/README.ja.md\n+++ b/README.ja.md\n").toByteArray() + hunkHeader + "\n$body".toByteArray()

    private fun mail(headers: String, body: String, stat: String, diff: ByteArray): ByteArray =
        ("From ae7bcbbb107c0b41d0f975c94d5cdde4b9d90684 Mon Sep 17 00:00:00 2001\n$headers\n$body\n---\n$stat\n\n").toByteArray() +
            diff + "-- \n2.43.0\n\n".toByteArray()

    private fun rejects(bytes: ByteArray): PatchFormatException? =
        runCatching { PatchParser.parse(bytes) }.exceptionOrNull() as? PatchFormatException

    /** 公 is E5 85 AC: its middle byte, read as Latin-1, is U+0085, a line terminator to a bare regex `.`. */
    @Test
    fun hunkHeaderWhoseSectionHoldsByte85IsKept() {
        val file = PatchParser.parse(diff("@@ -2,3 +2,3 @@ 公開".toByteArray())).files.single()
        assertEquals(1, file.hunks.size)
        assertEquals(1, file.additions)
        assertEquals(1, file.deletions)
        assertEquals("公開", file.hunks.single().section)
        // 1.0.0 dropped this hunk; the app marks pushes it made.
        assertEquals(1, file.droppedBy100)
    }

    @Test
    fun laterHunksCountAsDroppedBy100() {
        val p = "diff --git a/x b/x\n--- a/x\n+++ b/x\n@@ -1,1 +1,1 @@\n-a\n+b\n@@ -5,1 +5,1 @@ 公\n-c\n+d\n@@ -9,1 +9,1 @@\n-e\n+f\n"
        val f = PatchParser.parse(p.toByteArray()).files.single()
        assertEquals(3, f.hunks.size)
        assertEquals(2, f.droppedBy100)
    }

    /** だ is E3 81 A0; Kotlin's trim() would drop the A0 byte (it is a no-break space in Latin-1). */
    @Test
    fun sectionEndingInDaKeepsItsLastByte() {
        val f = PatchParser.parse(diff("@@ -2,3 +2,3 @@ これはペンだ".toByteArray())).files.single()
        assertEquals("これはペンだ", f.hunks.single().section)
    }

    @Test
    fun mailWithJapaneseSectionHeadings() {
        val patch = PatchParser.parse(
            mail("From: Test <test@example.com>\nDate: Mon, 26 Oct 2026 21:49:06 +0900\nSubject: [PATCH 1/1] docs\n", "body",
                " README.ja.md | 2 +-\n 1 file changed, 1 insertion(+), 1 deletion(-)", diff("@@ -2,3 +2,3 @@ 公開".toByteArray())),
        )
        assertEquals("README.ja.md", patch.files.single().newPath)
        assertEquals(1, patch.additions)
        assertEquals(1, patch.deletions)
    }

    @Test
    fun brokenHunkHeaderIsRejectedNotDropped() {
        val e = rejects(diff("@@ -2,3 +2,3 oops".toByteArray()))
        assertEquals(5, e?.line)
    }

    /** What 1.0.0 would have shown as a patch with one change less. */
    @Test
    fun diffstatThatDisagreesWithTheDiffIsRejected() {
        val bytes = mail("From: Test <test@example.com>\nSubject: [PATCH] docs\n", "body",
            " README.ja.md | 4 ++--\n 1 file changed, 2 insertions(+), 2 deletions(-)", diff("@@ -2,3 +2,3 @@".toByteArray()))
        assertTrue(rejects(bytes) is PatchCountException)
    }

    @Test
    fun linesBeyondTheCountsAreRejected() {
        // Counts say 2 old and 2 new lines; two more change lines follow and would have been ignored.
        for (index in listOf(true, false)) {
            val e = rejects(diff("@@ -2,2 +2,2 @@".toByteArray(), " a\n-b\n+c\n-d\n+e\n", index))
            assertTrue(e != null && e !is PatchCountException)
        }
    }

    @Test
    fun aLineWithoutMarkerInsideAHunkIsRejected() {
        // A context line that lost its leading space (copied from a chat) would end the hunk early;
        // the change after it must not be dropped.
        for (index in listOf(true, false)) {
            assertTrue(rejects(diff("@@ -2,4 +2,4 @@".toByteArray(), " a\nfun main() {\n-b\n+c\n d\n", index)) != null)
        }
    }

    @Test
    fun countsTooLargeBeforeProseAreTolerated() {
        val f = PatchParser.parse(diff("@@ -2,9 +2,9 @@".toByteArray(), " a\n-b\n+c\n d\n\nThis should fix it.\n", index = false)).files.single()
        assertEquals(3, f.hunks.single().oldCount)
        assertEquals(1, f.additions)
    }

    /** git never writes wrong counts, so a short hunk in a diff with an index line was cut off. */
    @Test
    fun gitDiffCutShortIsRejected() {
        assertTrue(rejects(diff("@@ -2,9 +2,9 @@".toByteArray(), " a\n-b\n+c\n d\n")) != null)
        val headerOnly = "diff --git a/x b/x\nindex 324633b..356bed5 100644\n--- a/x\n+++ b/x\n"
        assertTrue(rejects(headerOnly.toByteArray()) != null)
    }

    @Test
    fun countsTooLargeBeforeTheNextFileAreTolerated() {
        val p = "--- a/x\n+++ b/x\n@@ -1,5 +1,5 @@\n-a\n+b\n--- a/y\n+++ b/y\n@@ -1 +1 @@\n-c\n+d\n"
        val files = PatchParser.parse(p.toByteArray()).files
        assertEquals(listOf("x", "y"), files.map { it.path })
        assertEquals(2, files.sumOf { it.additions })
    }

    /** A removed "-- x" line followed by an added "++ y" line looks like a file header, but the counts say otherwise. */
    @Test
    fun removedDashDashLineIsContentWhenCountsSaySo() {
        val p = "diff --git a/q.sql b/q.sql\n--- a/q.sql\n+++ b/q.sql\n@@ -1,3 +1,3 @@\n select 1;\n--- old comment\n+++ new\n select 2;\n"
        val f = PatchParser.parse(p.toByteArray()).files.single()
        assertEquals(1, f.additions)
        assertEquals(1, f.deletions)
        assertEquals("-- old comment\n", String(f.hunks.single().lines[1].bytes))
    }

    @Test
    fun quotedPrintableBodyIsDecoded() {
        val qp = "diff --git a/x b/x\n--- a/x\n+++ b/x\n@@ -1 +1 @@\n-a =3D 1\n+a =3D 2 =E5=85=AC=\n=E9=96=8B\n"
        val p = PatchParser.parse(
            ("From: A <a@example.com>\nSubject: [PATCH] x\nContent-Type: text/plain; charset=UTF-8\nContent-Transfer-Encoding: quoted-printable\n\n" +
                "msg\n---\n x | 2 +-\n 1 file changed, 1 insertion(+), 1 deletion(-)\n\n$qp").toByteArray(),
        )
        val lines = p.files.single().hunks.single().lines
        assertEquals("a = 1\n", String(lines[0].bytes))
        assertEquals("a = 2 公開\n", String(lines[1].bytes))
    }

    @Test
    fun inBodyFromWithJapaneseName() {
        val p = PatchParser.parse(
            mail("From: Sender <s@example.com>\nSubject: [PATCH] x\n", "From: 山田 太郎 <taro@example.jp>\n\nbody",
                " README.ja.md | 2 +-\n 1 file changed, 1 insertion(+), 1 deletion(-)", diff("@@ -2,3 +2,3 @@".toByteArray())),
        )
        assertEquals("山田 太郎", p.commits.single().authorName)
        assertEquals("taro@example.jp", p.commits.single().authorEmail)
    }

    @Test
    fun encodedWordsSplitInsideACharacter() {
        // 日 = E6 97 A5, split across two encoded words.
        assertEquals("日本", PatchParser.decodeHeader("=?UTF-8?q?=E6=97?= =?UTF-8?q?=A5=E6=9C=AC?="))
        assertEquals("a b", PatchParser.decodeHeader("=?UTF-8?q?a?= b"))
    }

    @Test
    fun combinedDiffIsRejected() {
        val p = "diff --cc x\nindex 1,2..3\n--- a/x\n+++ b/x\n@@@ -1,1 -1,1 +1,1 @@@\n- a\n -b\n++c\n"
        assertTrue(rejects(p.toByteArray()) != null)
    }

    @Test
    fun traditionalPathEndingInDa() {
        val p = "--- メモだ\n+++ メモだ\n@@ -1 +1 @@\n-a\n+b\n"
        assertEquals("メモだ", PatchParser.parse(p.toByteArray()).files.single().path)
    }

    @Test
    fun coverLetterIsNotACommit() {
        val cover = ("From 1111111111111111111111111111111111111111 Mon Sep 17 00:00:00 2001\nFrom: A <a@example.com>\n" +
            "Subject: [PATCH 0/1] *** SUBJECT HERE ***\n\n*** BLURB HERE ***\n\nA (1):\n  docs\n\n" +
            " README.ja.md | 2 +-\n 1 file changed, 1 insertion(+), 1 deletion(-)\n\n-- \n2.43.0\n\n").toByteArray()
        val one = mail("From: A <a@example.com>\nSubject: [PATCH 1/1] docs\n", "body",
            " README.ja.md | 2 +-\n 1 file changed, 1 insertion(+), 1 deletion(-)", diff("@@ -2,3 +2,3 @@".toByteArray()))
        val p = PatchParser.parse(cover + one)
        assertEquals(listOf("docs"), p.commits.map { it.subject })
    }

    @Test
    fun mailSignatureAfterLastHunkIsNotContent() {
        val f = PatchParser.parse(
            mail("From: A <a@example.com>\nSubject: [PATCH] x\n", "body",
                " README.ja.md | 2 +-\n 1 file changed, 1 insertion(+), 1 deletion(-)", diff("@@ -2,3 +2,3 @@".toByteArray())),
        ).files.single()
        assertFalse(f.hunks.single().lines.any { String(it.bytes).startsWith("-") })
    }
}
