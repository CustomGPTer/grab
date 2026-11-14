package uk.jj.grab

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Checks the parts that can be proven off-device, against lookup results captured from live sites. */
class EngineTest {

    private fun fixture(name: String) = javaClass.classLoader!!.getResource(name)!!.readText()

    // ---- reading what the engine found ----

    @Test fun `BBC video page - a one-item list - becomes a single video with real resolutions`() {
        val v = Yt.parse(fixture("bbc_single.json")) as Lookup.Video
        assertEquals("Watch: Archaeologist discover ancient tomb in Peru", v.title)
        assertEquals(1, v.item)                                   // fetched as "item 1 of the page"
        assertNull(v.url)
        assertEquals("720p", v.choices.first().label)             // highest first; no separate "Best" tile
        assertEquals(listOf(720, 540, 396, 360, 288, 252, 216, 144, 108), v.choices.filter { it.res > 0 }.map { it.res })
        assertTrue(v.choices.all { it.size == 0L })                // this site states no sizes, so none are shown
        assertEquals(listOf("m4a", "mp3"), v.choices.mapNotNull { it.audioFormat })
    }

    @Test fun `caught stream manifest gives its resolutions`() {
        val v = Yt.parse(fixture("stream.json")) as Lookup.Video
        assertEquals(0, v.item)
        assertEquals(listOf(720, 540, 396, 288, 216), v.choices.filter { it.res > 0 }.map { it.res })
    }

    @Test fun `channel listing becomes a tick list of separately addressed videos`() {
        val p = Yt.parse(fixture("vimeo_flat.json")) as Lookup.Playlist
        assertEquals(40, p.entries.size)
        assertTrue(p.entries.all { it.item == 0 && it.url.startsWith("https://vimeo.com/") })
        assertEquals("PSYCHONAUTS", p.entries.first().title)
    }

    @Test fun `entries the site gives no title for get a placeholder, never the text null`() {
        val p = Yt.parse(fixture("ted_flat.json")) as Lookup.Playlist
        assertEquals(25, p.entries.size)
        assertEquals("Video 1", p.entries.first().title)
        assertFalse(p.entries.any { it.title == "null" || it.url == "null" })
    }

    @Test(expected = EngineException::class) fun `empty output is reported, not crashed on`() { Yt.parse("") }

    @Test fun `tile sizes are the site's exact figures only - video plus its audio track, never an estimate`() {
        val mb = 1_048_576L
        val json = """{"title":"T","formats":[
          {"format_id":"a1","vcodec":"none","acodec":"opus","ext":"webm","tbr":160,"filesize":${30 * mb}},
          {"format_id":"a2","vcodec":"none","acodec":"mp4a.40.2","ext":"m4a","tbr":128,"filesize":${20 * mb}},
          {"format_id":"v1080","vcodec":"avc1.640028","acodec":"none","ext":"mp4","height":1080,"width":1920,"fps":30,"tbr":4000,"filesize":${200 * mb}},
          {"format_id":"v1080vp9","vcodec":"vp09","acodec":"none","ext":"webm","height":1080,"width":1920,"fps":30,"tbr":2500,"filesize":${120 * mb}},
          {"format_id":"v720","vcodec":"avc1.4d401f","acodec":"none","ext":"mp4","height":720,"width":1280,"fps":30,"tbr":2000,"filesize":null,"filesize_approx":${100 * mb}},
          {"format_id":"m360","vcodec":"avc1.42001E","acodec":"mp4a.40.2","ext":"mp4","height":360,"width":640,"fps":30,"tbr":700,"filesize":${50 * mb}},
          {"format_id":"sb","vcodec":"none","acodec":"none","ext":"mhtml","height":90,"width":160}
        ]}"""
        val c = (Yt.parse(json) as Lookup.Video).choices
        assertEquals(listOf("1080p", "720p", "360p", "M4A", "MP3"), c.map { it.label })
        assertEquals(220 * mb, c[0].size)        // H.264 video (the one the engine picks) + the AAC audio track
        assertEquals(0L, c[1].size)              // the site only gave a guess for 720p: nothing shown
        assertEquals(50 * mb, c[2].size)         // already has sound: its own size alone
        assertEquals(20 * mb, c[3].size)         // M4A audio as the site has it
        assertEquals(0L, c[4].size)              // MP3 is converted, so its size is not known in advance
    }

    @Test fun `no resolutions stated - a single Best tile`() {
        val c = (Yt.parse("""{"title":"T","formats":[{"format_id":"0","ext":"mp4","vcodec":null,"acodec":null}]}""") as Lookup.Video).choices
        assertEquals(listOf("Best", "M4A", "MP3"), c.map { it.label })
        assertEquals(0, c[0].res)
    }

    // ---- the commands sent to the engine ----

    private fun cmd(t: Task) = Yt.downloadRequest(t, File("/tmp/x"), null).buildCommand()

    private fun List<String>.arg(flag: String) = this[indexOf(flag) + 1]

    @Test fun `best quality video command`() {
        val c = cmd(Task(id = "1", url = "https://example.com/v", title = "T"))
        assertEquals("bv*+ba/b", c.arg("-f"))
        assertEquals("res,fps,vcodec:h264,acodec:aac", c.arg("-S"))
        assertEquals("mp4", c.arg("--merge-output-format"))
        assertEquals("mp4", c.arg("--remux-video"))
        assertEquals("/tmp/x/%(title).150B.%(ext)s", c.arg("-o"))
        assertTrue("--no-playlist" in c)
        assertTrue("--embed-subs" in c)                  // subtitles are on by default
        assertEquals(Yt.subtitleLanguages(), c.arg("--sub-langs"))
        assertFalse("--download-sections" in c)
        assertFalse("--embed-thumbnail" in c)            // cover art is for audio files only
        assertFalse("--user-agent" in c)                 // no forced browser identity on a plain download
        assertFalse("--cookies" in c)
        assertFalse("--playlist-items" in c)
        assertEquals("https://example.com/v", c.last())
    }

    @Test fun `chosen resolution and page item`() {
        val c = cmd(Task(id = "1", url = "https://example.com/v", title = "T", res = 720, playlistItem = 3))
        assertEquals("res:720,fps,vcodec:h264,acodec:aac", c.arg("-S"))
        assertEquals("3", c.arg("--playlist-items"))
    }

    @Test fun `audio only command`() {
        val c = cmd(Task(id = "1", url = "https://example.com/v", title = "T", audioFormat = "mp3"))
        assertEquals("ba/b", c.arg("-f"))
        assertEquals("mp3", c.arg("--audio-format"))
        assertTrue("-x" in c)
        assertFalse("--remux-video" in c)
        assertFalse("--embed-subs" in c)
        assertTrue("--embed-thumbnail" in c)             // cover art and tags are on by default
        assertTrue("--embed-metadata" in c)
        assertEquals("jpg", c.arg("--convert-thumbnails"))
    }

    @Test fun `just a section - only that part is asked for`() {
        val c = cmd(Task(id = "1", url = "https://example.com/v", title = "T", section = "90-150"))
        assertEquals("*90-150", c.arg("--download-sections"))
    }

    @Test fun `times typed on the section fields`() {
        assertEquals(90, Yt.parseClock("1:30"))
        assertEquals(45, Yt.parseClock("45"))
        assertEquals(3723, Yt.parseClock("1:02:03"))
        assertEquals(0, Yt.parseClock(" 0:00 "))
        assertNull(Yt.parseClock(""))
        assertNull(Yt.parseClock("1:"))
        assertNull(Yt.parseClock("abc"))
        assertNull(Yt.parseClock("1:2:3:4"))
        assertNull(Yt.parseClock("-5"))
    }

    @Test fun `subtitle languages - phone language first, then English`() {
        assertEquals("en.*,en", Yt.subtitleLanguages("en"))
        assertEquals("fr.*,fr,en.*,en", Yt.subtitleLanguages("fr"))
    }

    @Test fun `caught stream carries the page as referer, the browser identity and the page title as file name`() {
        val c = cmd(Task(id = "1", url = "https://cdn.example.net/a/master.m3u8", title = "T",
            referer = "https://site.example/watch/1", userAgent = "UA/1", fixedName = "100% real title"))
        assertEquals("Referer:https://site.example/watch/1", c.arg("--add-headers"))
        assertEquals("UA/1", c.arg("--user-agent"))
        assertEquals("/tmp/x/100%% real title.%(ext)s", c.arg("-o"))     // % escaped so the engine prints it literally
    }

    // ---- saved queue ----

    @Test fun `task survives being written to disk and read back`() {
        val t = Task(id = "abc", url = "https://e.com/v", title = "A \"quoted\" title", res = 1080, audioFormat = null,
            cookies = true, referer = "https://e.com/p", userAgent = "UA", fixedName = "name", pageUrl = "https://e.com/p",
            playlistItem = 2, privateFile = true, privateSession = true, section = "30-50", startAt = 1_800_000_000_000,
            status = Status.PAUSED, progress = 42.5f, error = "boom", log = "line1\nline2", outputUri = null, outputName = null)
        val back = Task.fromJson(JSONObject(t.toJson().toString()))
        assertEquals(t, back)
        assertEquals(Status.PAUSED, back.status)         // a paused download is still paused after a restart
        assertNull(back.audioFormat)                     // absent stays absent, not the text "null"
        assertNull(back.outputUri)
    }

    // ---- stream detection ----

    @Test fun `recognises manifests and media files, ignores stream segments and other files`() {
        assertEquals("HLS", Sniffer.classify("https", "/video/master.m3u8"))
        assertEquals("DASH", Sniffer.classify("https", "/a/b/manifest.mpd"))
        assertEquals("Video file", Sniffer.classify("https", "/media/clip-720p.MP4"))
        assertEquals("Audio file", Sniffer.classify("http", "/podcast/episode12.mp3"))
        assertNull(Sniffer.classify("https", "/hls/seg-12.ts"))
        assertNull(Sniffer.classify("https", "/dash/chunk-3.m4s"))
        assertNull(Sniffer.classify("https", "/dash/init.mp4"))
        assertNull(Sniffer.classify("https", "/dash/video/seg_00012.mp4"))
        assertNull(Sniffer.classify("https", "/dash/audio/frag-4.m4a"))
        assertNull(Sniffer.classify("https", "/img/poster.jpg"))
        assertNull(Sniffer.classify("blob", "/x.mp4"))
        assertNull(Sniffer.classify("https", null))
    }

    // ---- ad blocking, against the list that ships in the app ----

    @Test fun `blocklist blocks ad hosts and their subdomains, never ordinary sites or the page's own hosts`() {
        File("src/main/assets/adhosts.txt").bufferedReader().use { AdBlock.load(it) }
        assertTrue(AdBlock.isBlocked("doubleclick.net"))
        assertTrue(AdBlock.isBlocked("ad.doubleclick.net"))
        assertTrue(AdBlock.isBlocked("pagead2.googlesyndication.com"))
        assertTrue(AdBlock.isBlocked("deep.sub.popads.net"))
        assertFalse(AdBlock.isBlocked("www.bbc.com"))
        assertFalse(AdBlock.isBlocked("www.youtube.com"))
        assertFalse(AdBlock.isBlocked("rr3---sn-aigl6nek.googlevideo.com"))
        assertFalse(AdBlock.isBlocked("vod-hls-ww-live.akamaized.net"))
        assertFalse(AdBlock.isBlocked("com"))
        assertFalse(AdBlock.isBlocked(null))
        assertTrue(AdBlock.shouldBlock("ad.doubleclick.net", "www.bbc.com"))
        assertFalse(AdBlock.shouldBlock("static.doubleclick.net", "www.doubleclick.net"))   // a site's own hosts are never blocked
    }

    // ---- helpers ----

    @Test fun `site grouping and file-name cleaning`() {
        assertEquals("bbc.co.uk", baseDomain("www.bbc.co.uk"))
        assertEquals("bbc.com", baseDomain("news.files.bbc.com"))
        assertEquals("example.com", baseDomain("example.com"))
        assertTrue(sameSite("a.example.com", "b.example.com"))
        assertFalse(sameSite("example.com", "example.org"))
        assertFalse(sameSite(null, null))
        assertEquals("Watch What happens next - Part 1", safeName("Watch: What happens/next?  - Part 1\n"))
        assertNull(safeName("  ??? "))
        assertEquals("", fmtEta(-1))
        assertEquals("1m 5s left", fmtEta(65))
    }
}
