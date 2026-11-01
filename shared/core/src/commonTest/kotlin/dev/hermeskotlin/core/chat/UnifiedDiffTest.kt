package dev.hermeskotlin.core.chat

import kotlin.test.Test
import kotlin.test.assertEquals

/** A gateway diff reads into lines that know their place on both sides. */
class UnifiedDiffTest {

    private val sample = """
        --- a/src/app.ts
        +++ b/src/app.ts
        @@ -10,4 +10,5 @@ export function main()
         const a = 1
        -const b = 2
        +const b = 3
        +const c = 4
         return a
    """.trimIndent()

    @Test
    fun eachLineKnowsItsNumberOnBothSides() {
        val file = parseUnifiedDiff(sample).single()
        assertEquals("src/app.ts", file.path)
        val changed = file.lines.filter { it.kind != DiffLineKind.Hunk && it.kind != DiffLineKind.Note }
        assertEquals(
            listOf(
                // kind, old number, new number, text without its sign
                listOf(DiffLineKind.Context, 10, 10, "const a = 1"),
                listOf(DiffLineKind.Removed, 11, null, "const b = 2"),
                listOf(DiffLineKind.Added, null, 11, "const b = 3"),
                listOf(DiffLineKind.Added, null, 12, "const c = 4"),
                listOf(DiffLineKind.Context, 12, 13, "return a"),
            ),
            changed.map { listOf(it.kind, it.oldNumber, it.newNumber, it.text) },
        )
        assertEquals(2, file.added)
        assertEquals(1, file.removed)
    }

    @Test
    fun twoFilesStayApart() {
        val files = parseUnifiedDiff(
            sample + "\n" + """
                --- a/docs/readme.md
                +++ b/docs/readme.md
                @@ -1 +1 @@
                -old
                +new
            """.trimIndent(),
        )
        assertEquals(listOf("src/app.ts", "docs/readme.md"), files.map { it.path })
        assertEquals(1, files[1].added)
    }

    @Test
    fun gitHeaderNamesTheFileEvenWithoutUnifiedHeaders() {
        val files = parseUnifiedDiff(
            """
            diff --git a/web/index.js b/web/index.js
            index 1111111..2222222 100644
            --- a/web/index.js
            +++ b/web/index.js
            @@ -1 +1 @@
            -let x = 1
            +let x = 2
            """.trimIndent(),
        )
        assertEquals("web/index.js", files.single().path)
    }

    @Test
    fun metadataBetweenHunksDoesNotBecomeContext() {
        // A hunk ends at its last line; the next file's `index` line must not be counted as unchanged code.
        val files = parseUnifiedDiff(
            """
            --- a/one.kt
            +++ b/one.kt
            @@ -1 +1 @@
            -a
            +b
            index 3333333..4444444 100644
            --- a/two.kt
            +++ b/two.kt
            @@ -5 +5 @@
            -c
            +d
            """.trimIndent(),
        )
        assertEquals(listOf("one.kt", "two.kt"), files.map { it.path })
        assertEquals(0, files[0].lines.count { it.kind == DiffLineKind.Context })
        assertEquals(listOf(5), files[1].lines.filter { it.kind == DiffLineKind.Removed }.map { it.oldNumber })
    }

    @Test
    fun aDeletedFileIsNamedByItsOldPath() {
        val files = parseUnifiedDiff(
            """
            --- a/old.txt
            +++ /dev/null
            @@ -1 +0,0 @@
            -gone
            """.trimIndent(),
        )
        assertEquals("old.txt", files.single().path)
    }

    @Test
    fun noSuchFileAtTheEndIsJustANote() {
        val file = parseUnifiedDiff(
            """
            --- a/x.py
            +++ b/x.py
            @@ -1 +1 @@
            -print(1)
            \ No newline at end of file
            +print(2)
            """.trimIndent(),
        ).single()
        assertEquals(1, file.lines.count { it.kind == DiffLineKind.Note })
        assertEquals(listOf(1 to null), file.lines.filter { it.kind == DiffLineKind.Removed }.map { it.oldNumber to it.newNumber })
    }

    @Test
    fun theLanguageComesFromThePath() {
        assertEquals("ts", languageOfPath("src/app.ts"))
        assertEquals("kt", languageOfPath("shared/x/Main.kt"))
        assertEquals("dockerfile", languageOfPath("docker/Dockerfile"))
        assertEquals(null, languageOfPath(""))
    }
}
