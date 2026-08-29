package com.niki914.nexus.agentic.repo

import org.junit.Assume.assumeNoException
import java.nio.file.FileSystemException
import java.nio.file.Files
import java.nio.file.Path

internal fun createSymbolicLinkOrSkip(link: Path, target: Path) {
    try {
        Files.createSymbolicLink(link, target)
    } catch (error: UnsupportedOperationException) {
        assumeNoException(error)
    } catch (error: SecurityException) {
        assumeNoException(error)
    } catch (error: FileSystemException) {
        if (System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)) {
            assumeNoException(error)
        } else {
            throw error
        }
    }
}
