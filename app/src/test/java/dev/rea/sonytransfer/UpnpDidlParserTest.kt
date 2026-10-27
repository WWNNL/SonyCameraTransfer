package dev.rea.sonytransfer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * DLNA Browse 响应（DIDL-Lite）解析测试。
 * 数据基于 2026-10-28 PC 端实机浏览结果：
 *   0 → PhotoRoot → Date → 2016-3-26 → DSC04309.JPG（4 个 res）
 */
class UpnpDidlParserTest {

    private val containerDidl = """<DIDL-Lite xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/" xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/"><container id="PhotoRoot" parentID="0" restricted="1" searchable="0" childCount="1"><dc:title>PhotoRoot</dc:title><upnp:class>object.container.storageFolder</upnp:class></container></DIDL-Lite>"""

    private val itemDidl = """<DIDL-Lite xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/" xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/"><item id="04_02_0545566624_000008_000014_000000" parentID="03_01_0545566624_000008_000000_000000" restricted="1"><dc:title>DSC04309.JPG</dc:title><upnp:class>object.item.imageItem.photo</upnp:class><res protocolInfo="http-get:*:image/jpeg:DLNA.ORG_PN=JPEG_LRG;DLNA.ORG_CI=1">http://192.168.122.1:60151/LRG_DSC04309.JPG?%2104%5f02</res><res protocolInfo="http-get:*:image/jpeg:DLNA.ORG_PN=JPEG_SM;DLNA.ORG_CI=1">http://192.168.122.1:60151/SM_DSC04309.JPG?%2104%5f02</res><res protocolInfo="http-get:*:image/jpeg:DLNA.ORG_PN=JPEG_TN;DLNA.ORG_CI=1">http://192.168.122.1:60151/TN_DSC04309.JPG?%2104%5f02</res><res protocolInfo="http-get:*:image/jpeg:*" size="11829248">http://192.168.122.1:60151/ORG_DSC04309.JPG?%2104%5f02</res></item></DIDL-Lite>"""

    @Test
    fun parsesContainerList() {
        val entries = Upnp.parseDidl(containerDidl)

        assertEquals(1, entries.size)
        assertEquals("PhotoRoot", entries[0].id)
        assertEquals("PhotoRoot", entries[0].title)
        assertTrue(entries[0].isContainer)
        assertEquals(1, entries[0].childCount)
        assertTrue(entries[0].resList.isEmpty())
    }

    @Test
    fun parsesPhotoItemAndPicksOriginalRes() {
        val entries = Upnp.parseDidl(itemDidl)

        assertEquals(1, entries.size)
        val entry = entries[0]
        assertEquals("DSC04309.JPG", entry.title)
        assertEquals(4, entry.resList.size)
        assertFalse(entry.isContainer)
        assertEquals("http-get:*:image/jpeg:*", entry.originalRes?.protocolInfo)
        assertEquals(11829248L, entry.originalRes?.sizeHint)
        assertTrue(entry.originalRes!!.url.contains("ORG_DSC04309.JPG"))
    }

    @Test
    fun keepsDocumentOrderForMixedChildren() {
        // 目录 + 文件混排时按 DIDL 文档顺序返回
        val mixed = """<DIDL-Lite xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/"><container id="03_01" childCount="2"><dc:title>2016-3-26</dc:title><upnp:class>object.container.storageFolder</upnp:class></container><item id="04_02"><dc:title>DSC04309.JPG</dc:title><upnp:class>object.item.imageItem.photo</upnp:class><res protocolInfo="http-get:*:image/jpeg:*" size="11829248">http://192.168.122.1:60151/ORG_DSC04309.JPG</res></item></DIDL-Lite>"""

        val entries = Upnp.parseDidl(mixed)

        assertEquals(2, entries.size)
        assertTrue(entries[0].isContainer)
        assertFalse(entries[1].isContainer)
    }

    @Test
    fun idAttrIsNotConfusedByParentId() {
        val didl = """<DIDL-Lite xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/"><container parentID="0" id="02_00_0545566624_000000_000000_000000" childCount="1"><dc:title>Date</dc:title><upnp:class>object.container.storageFolder</upnp:class></container></DIDL-Lite>"""

        val entries = Upnp.parseDidl(didl)

        assertEquals("02_00_0545566624_000000_000000_000000", entries[0].id)
        assertEquals("Date", entries[0].title)
    }

    @Test
    fun parsesEscapedResultXml() {
        // SOAP 响应里 <Result> 的内容是转义后的 DIDL
        val escaped = "&lt;DIDL-Lite&gt;&lt;container id=&quot;PhotoRoot&quot; childCount=&quot;1&quot;&gt;" +
            "&lt;dc:title&gt;PhotoRoot&lt;/dc:title&gt;&lt;upnp:class&gt;object.container&lt;/upnp:class&gt;" +
            "&lt;/container&gt;&lt;/DIDL-Lite&gt;"

        val entries = Upnp.parseDidl(Upnp.unescape(escaped))

        assertEquals(1, entries.size)
        assertEquals("PhotoRoot", entries[0].title)
        assertTrue(entries[0].isContainer)
    }

    @Test
    fun emptyDidlReturnsEmptyList() {
        assertTrue(Upnp.parseDidl("").isEmpty())
        assertNull(Upnp.parseDidl("<DIDL-Lite/>").firstOrNull())
    }
}
