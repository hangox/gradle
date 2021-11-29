/*
 * Copyright 2020 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.internal.watch.registry.impl

import net.rubygrapefruit.platform.file.FileSystemInfo
import net.rubygrapefruit.platform.file.FileWatcher
import org.gradle.api.internal.cache.StringInterner
import org.gradle.api.internal.file.TestFiles
import org.gradle.api.internal.file.TestVirtualFileSystem
import org.gradle.internal.file.FileMetadata.AccessType
import org.gradle.internal.file.impl.DefaultFileMetadata
import org.gradle.internal.snapshot.CaseSensitivity
import org.gradle.internal.snapshot.DirectorySnapshot
import org.gradle.internal.snapshot.FileSystemLocationSnapshot
import org.gradle.internal.snapshot.RegularFileSnapshot
import org.gradle.internal.snapshot.SnapshotHierarchy
import org.gradle.internal.snapshot.impl.DirectorySnapshotter
import org.gradle.internal.snapshot.impl.DirectorySnapshotterStatistics
import org.gradle.internal.vfs.impl.AbstractVirtualFileSystem
import org.gradle.internal.vfs.impl.DefaultSnapshotHierarchy
import org.gradle.internal.watch.registry.FileWatcherProbeRegistry
import org.gradle.internal.watch.registry.FileWatcherUpdater
import org.gradle.internal.watch.vfs.WatchMode
import org.gradle.internal.watch.vfs.WatchableFileSystemDetector
import org.gradle.test.fixtures.file.CleanupTestDirectory
import org.gradle.test.fixtures.file.TestFile
import org.gradle.test.fixtures.file.TestNameTestDirectoryProvider
import org.junit.Rule
import spock.lang.Specification

import java.nio.file.Files
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.atomic.AtomicBoolean
import java.util.function.Function
import java.util.function.Predicate
import java.util.stream.Stream

@CleanupTestDirectory
abstract class AbstractFileWatcherUpdaterTest extends Specification {
    @Rule
    TestNameTestDirectoryProvider temporaryFolder = new TestNameTestDirectoryProvider(getClass())

    def watcher = Mock(FileWatcher)
    def ignoredForWatching = [] as Set<String>
    Predicate<String> watchFilter = { path -> !ignoredForWatching.contains(path) }
    def watchableFileSystemDetector = Stub(WatchableFileSystemDetector)
    def probeLocationResolver = { hierarchy -> new File(hierarchy, ".gradle/file-watching.probe") } as Function<File, File>
    def probeRegistry = Stub(FileWatcherProbeRegistry)
    def watchableHiearchies = new WatchableHierarchies(probeRegistry, watchableFileSystemDetector, watchFilter)
    def directorySnapshotter = new DirectorySnapshotter(TestFiles.fileHasher(), new StringInterner(), [], Stub(DirectorySnapshotterStatistics.Collector))
    FileWatcherUpdater updater
    def virtualFileSystem = new TestVirtualFileSystem(DefaultSnapshotHierarchy.empty(CaseSensitivity.CASE_SENSITIVE)) {
        @Override
        protected SnapshotHierarchy updateNotifyingListeners(AbstractVirtualFileSystem.UpdateFunction updateFunction) {
            def diffListener = new SnapshotCollectingDiffListener()
            def newRoot = updateFunction.update(diffListener)
            diffListener.publishSnapshotDiff { removed, added ->
                updater.virtualFileSystemContentsChanged(removed, added, newRoot)
            }
            return newRoot
        }
    }

    def setup() {
        updater = createUpdater(watcher, watchableHiearchies)
    }

    abstract int getIfNonHierarchical()

    abstract FileWatcherUpdater createUpdater(FileWatcher watcher, WatchableHierarchies watchableHierarchies)

    def "does not watch directories outside of hierarchies to watch"() {
        def watchableHierarchies = ["first", "second", "third"].collect { file(it).createDir() }
        def fileOutsideOfWatchableHierarchies = file("forth").file("someFile.txt")

        when:
        registerWatchableHierarchies(watchableHierarchies)
        then:
        0 * _

        when:
        fileOutsideOfWatchableHierarchies.text = "hello"
        addSnapshot(snapshotRegularFile(fileOutsideOfWatchableHierarchies))
        then:
        0 * _
        vfsHasSnapshotsAt(fileOutsideOfWatchableHierarchies)

        when:
        buildFinished()
        then:
        0 * _
        !vfsHasSnapshotsAt(fileOutsideOfWatchableHierarchies)
    }

    def "retains files in hierarchies ignored for watching"() {
        def watchableHierarchy = file("watchable").createDir()
        def fileOutsideOfWatchableHierarchy = file("outside").file("someFile.txt").createFile()
        def fileInDirectoryIgnoredForWatching = file("cache/some-cache/someFile.txt").createFile()
        ignoredForWatching.add(fileInDirectoryIgnoredForWatching.absolutePath)

        when:
        registerWatchableHierarchies([watchableHierarchy])
        then:
        0 * _

        when:
        addSnapshot(snapshotRegularFile(fileOutsideOfWatchableHierarchy))
        addSnapshot(snapshotRegularFile(fileInDirectoryIgnoredForWatching))
        then:
        0 * _
        vfsHasSnapshotsAt(fileOutsideOfWatchableHierarchy)
        vfsHasSnapshotsAt(fileInDirectoryIgnoredForWatching)

        when:
        buildFinished()
        then:
        0 * _
        !vfsHasSnapshotsAt(fileOutsideOfWatchableHierarchy)
        vfsHasSnapshotsAt(fileInDirectoryIgnoredForWatching)
    }

    def "fails when discovering a hierarchy to watch and there is already something in the VFS"() {
        def watchableHierarchy = file("watchable").createDir()
        def fileInWatchableHierarchy = watchableHierarchy.file("some/dir/file.txt").createFile()

        when:
        addSnapshot(snapshotRegularFile(fileInWatchableHierarchy))
        then:
        0 * _

        when:
        registerWatchableHierarchies([watchableHierarchy])
        then:
        def exception = thrown(IllegalStateException)
        exception.message == "Found existing snapshot at '${fileInWatchableHierarchy.absolutePath}' for unwatched hierarchy '${watchableHierarchy.absolutePath}'"
    }

    def "does not watch symlinks and removes symlinks at the end of the build"() {
        def watchableHierarchy = file("watchable").createDir()
        def symlinkInWatchableHierarchy = watchableHierarchy.file("some/dir/file.txt").createFile()

        when:
        registerWatchableHierarchies([watchableHierarchy])
        addSnapshot(snapshotSymlinkedFile(symlinkInWatchableHierarchy))
        then:
        vfsHasSnapshotsAt(symlinkInWatchableHierarchy)
        0 * _

        when:
        buildFinished()
        then:
        !vfsHasSnapshotsAt(symlinkInWatchableHierarchy)
        0 * _
    }

    def "does not watch ignored files in a hierarchy to watch"() {
        def watchableHierarchy = file("watchable").createDir()
        def ignoredFileInHierarchy = watchableHierarchy.file("caches/cacheFile").createFile()
        ignoredForWatching.add(ignoredFileInHierarchy.absolutePath)
        ignoredForWatching.add(ignoredFileInHierarchy.parentFile.absolutePath)

        when:
        registerWatchableHierarchies([watchableHierarchy])
        addSnapshot(snapshotRegularFile(ignoredFileInHierarchy))
        then:
        vfsHasSnapshotsAt(ignoredFileInHierarchy)
        0 * _

        when:
        buildFinished()
        then:
        vfsHasSnapshotsAt(ignoredFileInHierarchy)
        0 * _
    }

    def "fails when hierarchy to watch is ignored"() {
        def watchableHierarchy = file("watchable").createDir()
        ignoredForWatching.add(watchableHierarchy.absolutePath)

        when:
        registerWatchableHierarchies([watchableHierarchy])
        then:
        def exception = thrown(IllegalStateException)
        exception.message == "Unable to watch directory '${watchableHierarchy.absolutePath}' since it is within Gradle's caches"
    }

    def "stops watching hierarchies when maximum number of hierarchies to watch has been reached"() {
        int maxHierarchiesToWatch = 4
        def oldestRegisteredWatchableHierarchy = file("oldestWatchable").createDir()
        def watchableHierarchies = (1..maxHierarchiesToWatch - 1).collect { index -> file("watchable${index}").createDir() }
        def newestRegisteredWatchableHierarchy = file("newestWatchable").createDir()

        when:
        registerWatchableHierarchies([oldestRegisteredWatchableHierarchy] + watchableHierarchies)
        then:
        0 * _

        when:
        ([oldestRegisteredWatchableHierarchy] + watchableHierarchies).each {
            addSnapshot(snapshotRegularFile(it.file("watched.txt").createFile()))
        }
        then:
        ([oldestRegisteredWatchableHierarchy] + watchableHierarchies).each { watchableHierarchy ->
            1 * watcher.startWatching({ equalIgnoringOrder(it, [watchableHierarchy]) })
        }
        when:
        registerWatchableHierarchies([newestRegisteredWatchableHierarchy])
        addSnapshot(snapshotRegularFile(newestRegisteredWatchableHierarchy.file("watched.txt").createFile()))
        then:
        1 * watcher.startWatching({ equalIgnoringOrder(it, [newestRegisteredWatchableHierarchy]) })

        when:
        buildFinished(maxHierarchiesToWatch)
        then:
        1 * watcher.stopWatching({ equalIgnoringOrder(it, [oldestRegisteredWatchableHierarchy]) })

        !vfsHasSnapshotsAt(oldestRegisteredWatchableHierarchy)
        vfsHasSnapshotsAt(newestRegisteredWatchableHierarchy)
    }

    def "does not start watching unsupported file system for default watch mode"() {
        def unsupportedFileSystemMountPoint = file("unsupported").createDir()
        def unwatchableContent = unsupportedFileSystemMountPoint.file("file.txt").createFile()
        def unsupportedFileSystem = Stub(FileSystemInfo) {
            getMountPoint() >> unsupportedFileSystemMountPoint
            getFileSystemType() >> "unsupported"
        }
        watchableFileSystemDetector.detectUnsupportedFileSystems() >> { it -> Stream.of(unsupportedFileSystem) }

        when:
        buildStarted(WatchMode.DEFAULT)
        registerWatchableHierarchies([unsupportedFileSystemMountPoint])
        addSnapshot(snapshotRegularFile(unwatchableContent))
        then:
        vfsHasSnapshotsAt(unwatchableContent)
        0 * _

        when:
        buildFinished(Integer.MAX_VALUE, WatchMode.DEFAULT)
        then:
        !vfsHasSnapshotsAt(unwatchableContent)
        0 * _
    }

    def "starts watching unsupported file system when watching is enabled"() {
        def unsupportedFileSystemMountPoint = file("unsupported").createDir()
        def unwatchableContent = unsupportedFileSystemMountPoint.file("file.txt").createFile()
        def unsupportedFileSystem = Stub(FileSystemInfo) {
            getMountPoint() >> unsupportedFileSystemMountPoint
            getFileSystemType() >> "unsupported"
        }
        watchableFileSystemDetector.detectUnsupportedFileSystems() >> { it -> Stream.of(unsupportedFileSystem) }

        when:
        buildStarted(WatchMode.ENABLED)
        registerWatchableHierarchies([unsupportedFileSystemMountPoint])
        addSnapshot(snapshotRegularFile(unwatchableContent))
        then:
        vfsHasSnapshotsAt(unwatchableContent)
        1 * watcher.startWatching({ equalIgnoringOrder(it, [unsupportedFileSystemMountPoint]) })
        ifNonHierarchical * watcher.startWatching({ equalIgnoringOrder(it, [probeRegistry.getProbeDirectory(unsupportedFileSystemMountPoint)]) })
        0 * _

        when:
        buildFinished(Integer.MAX_VALUE, WatchMode.ENABLED)
        then:
        vfsHasSnapshotsAt(unwatchableContent)
        0 * _
    }

    def "stops watching unsupported file system"() {
        def unsupportedFileSystemMountPoint = file("unsupported").createDir()
        def unwatchableContent = unsupportedFileSystemMountPoint.file("file.txt").createFile()
        def unsupportedFileSystem = Stub(FileSystemInfo) {
            getMountPoint() >> unsupportedFileSystemMountPoint
            getFileSystemType() >> "unsupported"
        }
        watchableFileSystemDetector.detectUnsupportedFileSystems() >> { it -> Stream.of(unsupportedFileSystem) }

        when:
        buildStarted(WatchMode.ENABLED)
        registerWatchableHierarchies([unsupportedFileSystemMountPoint])
        addSnapshot(snapshotRegularFile(unwatchableContent))
        then:
        vfsHasSnapshotsAt(unwatchableContent)
        1 * watcher.startWatching({ equalIgnoringOrder(it, [unsupportedFileSystemMountPoint]) })
        ifNonHierarchical * watcher.startWatching({ equalIgnoringOrder(it, [probeRegistry.getProbeDirectory(unsupportedFileSystemMountPoint)]) })
        0 * _

        when:
        buildFinished(Integer.MAX_VALUE, WatchMode.ENABLED)
        then:
        vfsHasSnapshotsAt(unwatchableContent)
        0 * _

        when:
        registerWatchableHierarchies([unsupportedFileSystemMountPoint])
        buildStarted(WatchMode.DEFAULT)
        addSnapshot(snapshotRegularFile(unwatchableContent))
        then:
        vfsHasSnapshotsAt(unwatchableContent)
        1 * watcher.stopWatching({ equalIgnoringOrder(it, [unsupportedFileSystemMountPoint]) })
        ifNonHierarchical * watcher.stopWatching({ equalIgnoringOrder(it, [probeRegistry.getProbeDirectory(unsupportedFileSystemMountPoint)]) })
        0 * _

        when:
        buildFinished(Integer.MAX_VALUE, WatchMode.ENABLED)
        then:
        !vfsHasSnapshotsAt(unwatchableContent)
        0 * _
    }

    def "starts watching unsupported file system when watching becomes enabled"() {
        def unsupportedFileSystemMountPoint = file("unsupported").createDir()
        def unwatchableContent = unsupportedFileSystemMountPoint.file("file.txt").createFile()
        def unsupportedFileSystem = Stub(FileSystemInfo) {
            getMountPoint() >> unsupportedFileSystemMountPoint
            getFileSystemType() >> "unsupported"
        }
        watchableFileSystemDetector.detectUnsupportedFileSystems() >> { it -> Stream.of(unsupportedFileSystem) }

        when:
        buildStarted(WatchMode.DEFAULT)
        registerWatchableHierarchies([unsupportedFileSystemMountPoint])
        addSnapshot(snapshotRegularFile(unwatchableContent))
        then:
        vfsHasSnapshotsAt(unwatchableContent)
        0 * _

        when:
        buildFinished(Integer.MAX_VALUE, WatchMode.ENABLED)
        then:
        !vfsHasSnapshotsAt(unwatchableContent)

        when:
        registerWatchableHierarchies([unsupportedFileSystemMountPoint])
        buildStarted(WatchMode.ENABLED)
        addSnapshot(snapshotRegularFile(unwatchableContent))
        then:
        vfsHasSnapshotsAt(unwatchableContent)
        1 * watcher.startWatching({ equalIgnoringOrder(it, [unsupportedFileSystemMountPoint]) })
        ifNonHierarchical * watcher.startWatching({ equalIgnoringOrder(it, [probeRegistry.getProbeDirectory(unsupportedFileSystemMountPoint)]) })
        0 * _
    }

    def "watching continues for watched hierarchies that are confirmed by watch probe"() {
        def watchableHierarchy = file("watchable").createDir()
        def watchableHierarchyProbeDir = watchableHierarchy.file(".gradle")
        def fileInWatchableHierarchy = watchableHierarchy.file("file.txt").createFile()

        def notWatchedHierarchy = file("not-watched").createDir()
        def notWatchedHierarchyProbeDir = notWatchedHierarchy.file(".gradle")
        def fileInNotWatchedHierarchy = notWatchedHierarchy.file("file.txt").createFile()

        def watchableHierarchies = [watchableHierarchy, notWatchedHierarchy]

        when:
        registerWatchableHierarchies(watchableHierarchies)
        addSnapshot(snapshotRegularFile(fileInWatchableHierarchy))
        then:
        vfsHasSnapshotsAt(watchableHierarchy)
        1 * watcher.startWatching({ equalIgnoringOrder(it, [watchableHierarchy]) })
        ifNonHierarchical * watcher.startWatching({ equalIgnoringOrder(it, [watchableHierarchyProbeDir]) })
        _ * probeRegistry.getProbeDirectory(watchableHierarchy) >> watchableHierarchyProbeDir
        0 * _

        when:
        addSnapshot(snapshotRegularFile(fileInNotWatchedHierarchy))
        then:
        vfsHasSnapshotsAt(notWatchedHierarchy)

        1 * watcher.startWatching({ equalIgnoringOrder(it, [notWatchedHierarchy]) })
        ifNonHierarchical * watcher.startWatching({ equalIgnoringOrder(it, [notWatchedHierarchyProbeDir]) })
        _ * probeRegistry.getProbeDirectory(notWatchedHierarchy) >> notWatchedHierarchyProbeDir
        0 * _

        when:
        buildStarted()
        then:
        vfsHasSnapshotsAt(watchableHierarchy)
        !vfsHasSnapshotsAt(notWatchedHierarchy)

        1 * watcher.stopWatching({ equalIgnoringOrder(it, [notWatchedHierarchy]) })
        ifNonHierarchical * watcher.stopWatching({ equalIgnoringOrder(it, [notWatchedHierarchyProbeDir]) })
        _ * probeRegistry.getProbeDirectory(notWatchedHierarchy) >> notWatchedHierarchyProbeDir
        _ * probeRegistry.unprovenHierarchies() >> Stream.of(notWatchedHierarchy)
        0 * _
    }

    TestFile file(Object... path) {
        temporaryFolder.testDirectory.file(path)
    }

    DirectorySnapshot snapshotDirectory(File directory) {
        directorySnapshotter.snapshot(directory.absolutePath, null, new AtomicBoolean(false)) as DirectorySnapshot
    }

    void addSnapshot(FileSystemLocationSnapshot snapshot) {
        virtualFileSystem.store(snapshot.absolutePath, snapshot)
    }

    void invalidate(String absolutePath) {
        virtualFileSystem.invalidate([absolutePath])
    }

    void invalidate(FileSystemLocationSnapshot snapshot) {
        invalidate(snapshot.absolutePath)
    }

    static RegularFileSnapshot snapshotRegularFile(File regularFile) {
        def attributes = Files.readAttributes(regularFile.toPath(), BasicFileAttributes)
        new RegularFileSnapshot(
            regularFile.absolutePath,
            regularFile.name,
            TestFiles.fileHasher().hash(regularFile),
            DefaultFileMetadata.file(attributes.lastModifiedTime().toMillis(), attributes.size(), AccessType.DIRECT)
        )
    }

    static RegularFileSnapshot snapshotSymlinkedFile(File regularFile) {
        def attributes = Files.readAttributes(regularFile.toPath(), BasicFileAttributes)
        new RegularFileSnapshot(
            regularFile.absolutePath,
            regularFile.name,
            TestFiles.fileHasher().hash(regularFile),
            DefaultFileMetadata.file(attributes.lastModifiedTime().toMillis(), attributes.size(), AccessType.VIA_SYMLINK)
        )
    }

    static boolean equalIgnoringOrder(Object actual, Collection<?> expected) {
        List<?> actualSorted = (actual as List).toSorted()
        List<?> expectedSorted = (expected as List).toSorted()
        return actualSorted == expectedSorted
    }

    boolean vfsHasSnapshotsAt(File location) {
        return virtualFileSystem.root.rootSnapshotsUnder(location.absolutePath)
            .findAny()
            .present
    }

    void registerWatchableHierarchies(Iterable<File> watchableHierarchies) {
        watchableHierarchies.each { watchableHierarchy ->
            updater.registerWatchableHierarchy(watchableHierarchy, virtualFileSystem.root)
        }
    }

    File watchProbeFor(File watchableHierarchy) {
        probeLocationResolver.apply(watchableHierarchy)
    }

    SnapshotHierarchy buildStarted(watchMode = WatchMode.DEFAULT) {
        virtualFileSystem.root = updater.updateVfsOnBuildStarted(virtualFileSystem.root, watchMode)
        return virtualFileSystem.root
    }

    void buildFinished(int maximumNumberOfWatchedHierarchies = Integer.MAX_VALUE, watchMode = WatchMode.DEFAULT) {
        virtualFileSystem.root = updater.updateVfsOnBuildFinished(virtualFileSystem.root, watchMode, maximumNumberOfWatchedHierarchies)
    }
}
