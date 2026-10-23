package fr.pocketftp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class RemotePathsTest {
    @Test fun normalizePreventsTraversalAboveRoot() {
        assertEquals("/a/c", RemotePaths.normalize("/a/b/../c"))
        assertEquals("/", RemotePaths.normalize("../../"))
    }
    @Test fun childRejectsBadNames() {
        assertThrows(IllegalArgumentException::class.java) {
            RemotePaths.child("/public_html", "../secret")
        }
        assertEquals("/public_html/photo.png", RemotePaths.child("/public_html", "photo.png"))
    }
    @Test fun parentUsesRoot() {
        assertEquals("/", RemotePaths.parent("/a"))
        assertEquals("/", RemotePaths.parent("/"))
    }
    @Test fun nameReturnsFinalPathSegment() {
        assertEquals("photo.png", RemotePaths.name("/a/b/photo.png"))
        assertEquals("/", RemotePaths.name("/"))
    }
}
