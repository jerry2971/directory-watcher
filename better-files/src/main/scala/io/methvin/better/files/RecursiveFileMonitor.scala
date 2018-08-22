package io.methvin.better.files

import java.nio.file.Path
import java.nio.file.WatchEvent
import java.util.Collections

import better.files._
import io.methvin.watcher.DirectoryChangeEvent
import io.methvin.watcher.DirectoryChangeEvent.EventType
import io.methvin.watcher.DirectoryChangeListener
import io.methvin.watcher.DirectoryWatcher
import org.slf4j.Logger
import org.slf4j.helpers.NOPLogger

import scala.concurrent.ExecutionContext

/**
  * An implementation of the better-files `File.Monitor` interface using directory-watcher.
  *
  * @param root the root directory to watch
  * @param enableFileHashing `true` to hash files to prevent duplicate events, `false` to turn off hashing files.
  * @param logger the logger used by `DirectoryWatcher`. Useful to see debug output.
  */
abstract class RecursiveFileMonitor(
  val root: File,
  val enableFileHashing: Boolean = true,
  val logger: Logger = NOPLogger.NOP_LOGGER
) extends File.Monitor {

  protected[this] val pathToWatch: Option[Path] =
    if (root.exists) Some(if (root.isDirectory) root.path else root.parent.path) else None

  protected[this] def reactTo(path: Path): Boolean =
    path == null || root.isDirectory || root.isSamePathAs(path)

  protected[this] val watcher: DirectoryWatcher = DirectoryWatcher.builder
    .paths(pathToWatch.fold(Collections.emptyList[Path]())(Collections.singletonList))
    .listener(
      new DirectoryChangeListener {
        override def onEvent(event: DirectoryChangeEvent): Unit = {
          if (reactTo(event.path)) {
            val et = event.eventType
            et match {
              case EventType.OVERFLOW =>
                onUnknownEvent(new WatchEvent[AnyRef] {
                  override def kind = et.getWatchEventKind.asInstanceOf[WatchEvent.Kind[AnyRef]]
                  override def count = event.count
                  override def context = null
                })
              case _ =>
                RecursiveFileMonitor.this.onEvent(
                  et.getWatchEventKind.asInstanceOf[WatchEvent.Kind[Path]],
                  File(event.path),
                  event.count
                )
            }
          }
        }

        override def onException(e: Exception): Unit = {
          RecursiveFileMonitor.this.onException(e)
        }
      }
    )
    .fileHashing(enableFileHashing)
    .logger(logger)
    .build()

  override def start()(implicit executionContext: ExecutionContext): Unit = {
    executionContext.execute(() => watcher.watch())
  }

  override def close(): Unit = {
    watcher.close()
  }

  // override these so it works like the better-files monitor
  override def onCreate(file: File, count: Int): Unit = {}
  override def onModify(file: File, count: Int): Unit = {}
  override def onDelete(file: File, count: Int): Unit = {}
  override def onUnknownEvent(event: WatchEvent[_]): Unit = {}
  override def onException(exception: Throwable): Unit = {}
}
