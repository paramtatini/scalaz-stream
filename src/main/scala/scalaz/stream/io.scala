package scalaz.stream

import java.io._

import scalaz.concurrent.Task

import scodec.bits.ByteVector

import scala.annotation.tailrec
import scala.io.{Codec, Source}

import Process._

/**
 * Module of `Process` functions and combinators for file and network I/O.
 */
object io {

  // NB: methods are in alphabetical order

  /**
   * Like resource, but the `release` action may emit a final value,
   * useful for flushing any internal buffers. NB: In the event of an
   * error, this final value is ignored.
   */
  def bufferedResource[F[_],R,O](acquire: F[R])(
                            flushAndRelease: R => F[O])(
                            step: R => F[O]): Process[F,O] =
    await(acquire) { r =>
      repeatEval(step(r)).onComplete(eval(flushAndRelease(r)))
    }

  /**
   * Implementation of resource for channels where resource needs to be
   * flushed at the end of processing.
   */
  def bufferedChannel[R,I,O](acquire: Task[R])(
                             flush: R => Task[O])(
                             release: R => Task[Unit])(
                             step: R => Task[I => Task[O]]): Channel[Task,Option[I],O] = {
    resource(acquire)(release) {
      r =>
        val s = step(r)
        Task.now {
          case Some(i) => s flatMap (f => f(i))
          case None => flush(r)
        }
    }
  }

  /**
   * Creates a `Channel[Task,Int,ByteVector]` from an `InputStream` by
   * repeatedly requesting the given number of bytes. The last chunk
   * may be less than the requested size.
   *
   * This implementation requires an array allocation for each read.
   * To recycle the input buffer, use `unsafeChunkR`.
   *
   * This implementation closes the `InputStream` when finished
   * or in the event of an error.
   */
  def chunkR(is: => InputStream): Channel[Task,Int,ByteVector] =
    unsafeChunkR(is).map(f => (n: Int) => {
      val buf = new Array[Byte](n)
      f(buf).map(ByteVector.view)
    })

  /**
   * Creates a `Sink` from an `OutputStream`, which will be closed
   * when this `Process` is halted.
   */
  def chunkW(os: => OutputStream): Sink[Task,ByteVector] =
    resource(Task.delay(os))(os => Task.delay(os.close))(
      os => Task.now((bytes: ByteVector) => Task.delay(os.write(bytes.toArray))))

  /**
   * Creates a `Sink` from a file name and optional buffer size in bytes.
   *
   * @param append if true, then bytes will be written to the end of the file
   *               rather than the beginning
   */
  def fileChunkW(f: String, bufferSize: Int = 4096, append: Boolean = false): Sink[Task,ByteVector] =
    chunkW(new BufferedOutputStream(new FileOutputStream(f, append), bufferSize))

  /** Creates a `Channel` from a file name and optional buffer size in bytes. */
  def fileChunkR(f: String, bufferSize: Int = 4096): Channel[Task,Int,ByteVector] =
    chunkR(new BufferedInputStream(new FileInputStream(f), bufferSize))

  /** A `Sink` which, as a side effect, adds elements to the given `Buffer`. */
  def fillBuffer[A](buf: collection.mutable.Buffer[A]): Sink[Task,A] =
    channel.lift((a: A) => Task.delay { buf += a })

  /**
   * Creates a `Process[Task,String]` from the lines of a file, using
   * the `iteratorR` combinator to ensure the file is closed
   * when processing the stream of lines is finished.
   */
  def linesR(filename: String)(implicit codec: Codec): Process[Task,String] =
    linesR(Source.fromFile(filename)(codec))

  /**
   * Creates a `Process[Task,String]` from the lines of the `InputStream`,
   * using the `iteratorR` combinator to ensure the `InputStream` is closed
   * when processing the stream of lines is finished.
   */
  def linesR(in: => InputStream)(implicit codec: Codec): Process[Task,String] =
    linesR(Source.fromInputStream(in)(codec))

  /**
   * Creates a `Process[Task,String]` from the lines of the `Source`,
   * using the `iteratorR` combinator to ensure the `Source` is closed
   * when processing the stream of lines is finished.
   */
  def linesR(src: => Source): Process[Task,String] = {
    iteratorR(Task.delay(src))(src => Task.delay(src.close()))(r => Task.delay(r.getLines()))
  }

  /**
   * Creates `Sink` from an `PrintStream` using `f` to perform
   * specific side effects on that `PrintStream`.
   */
  def printStreamSink[O](out: PrintStream)(f: (PrintStream, O) => Unit): Sink[Task, O] =
    channel.lift((o: O) => Task.delay {
      f(out, o)
      if (out.checkError)
        throw Cause.Terminated(Cause.End)
    })

  /**
   * Turn a `PrintStream` into a `Sink`. This `Sink` does not
   * emit newlines after each element. For that, use `printLines`.
   */
  def print(out: PrintStream): Sink[Task,String] = printStreamSink(out)((ps, o) => ps.print(o))

  /**
   * Turn a `PrintStream` into a `Sink`. This `Sink` emits
   * newlines after each element. If this is not desired, use `print`.
   */
  def printLines(out: PrintStream): Sink[Task,String] = printStreamSink(out)((ps, o) => ps.println(o))

  /**
   * Generic combinator for producing a `Process[F,O]` from some
   * effectful `O` source. The source is tied to some resource,
   * `R` (like a file handle) that we want to ensure is released.
   * See `chunkW` for an example use.
   */
  def resource[F[_],R,O](acquire: F[R])(
                         release: R => F[Unit])(
                         step: R => F[O]): Process[F,O] =
    bracket(acquire)(r => eval_(release(r))){
      r => repeatEval(step(r))
    } onHalt { _.asHalt }

  /**
   * Create a Process from an iterator. The value behind the iterator should be
   * immutable and not rely on an external resource. If that is not the case, use
   * `io.iteratorR`.
   */
  def iterator[O](i: Task[Iterator[O]]): Process[Task, O] = {
    await(i) { iterator =>
      val hasNext = Task delay { iterator.hasNext }
      val next = Task delay { iterator.next() }

      def go: Process[Task, O] = await(hasNext) { hn => if (hn) eval(next) ++ go else halt }

      go
    }
  }

  /**
   * Create a Process from an iterator that is tied to some resource,
   * `R` (like a file handle) that we want to ensure is released.
   * See `linesR` for an example use.
   * @param req acquires the resource
   * @param release releases the resource
   * @param mkIterator creates the iterator from the resource
   * @tparam R is the resource
   * @tparam O is a value in the iterator
   * @return
   */
  def iteratorR[R, O](req: Task[R])(
                     release: R => Task[Unit])(
                     mkIterator: R => Task[Iterator[O]]): Process[Task, O] = {
    bracket[Task, R, O](req)(r => Process.eval_(release(r)))(r => iterator(mkIterator(r)) )
  }

  /**
   * The standard input stream, as `Process`. This `Process` repeatedly awaits
   * and emits chunks of bytes  from standard input.
   */
  def stdInBytes: Channel[Task, Int, ByteVector] =
    io.chunkR(System.in)

  /**
   * The standard input stream, as `Process`. This `Process` repeatedly awaits
   * and emits lines from standard input.
   */
  def stdInLines: Process[Task,String] =
    Process.repeatEval(Task delay { Option(scala.Console.readLine()) }) pipe process1.stripNone

  /**
   * The standard output stream, as a `Sink`. This `Sink` does not
   * emit newlines after each element. For that, use `stdOutLines`.
   */
  def stdOut: Sink[Task,String] =
    print(System.out)

  /**
   * The standard output stream, as a `ByteVector` `Sink`.
   */
  def stdOutBytes: Sink[Task, ByteVector] =
    chunkW(System.out)

  /**
   * The standard output stream, as a `Sink`. This `Sink` emits
   * newlines after each element. If this is not desired, use `stdOut`.
   */
  def stdOutLines: Sink[Task,String] =
    printLines(System.out)

  /**
   * Creates a `Channel[Task,Array[Byte],Array[Byte]]` from an `InputStream` by
   * repeatedly filling the input buffer. `is.read` will be called multiple times
   * as needed; however, the last chunk may be less than the requested size.
   *
   * It is safe to recycle the same buffer for consecutive reads
   * as long as whatever consumes this `Process` never stores the `Array[Byte]`
   * returned or pipes it to a combinator (like `buffer`) that does.
   * Use `chunkR` for a safe version of this combinator - this takes
   * an `Int` number of bytes to read and allocates a fresh `Array[Byte]`
   * for each read.
   *
   * This implementation closes the `InputStream` when finished
   * or in the event of an error.
   */
  def unsafeChunkR(is: => InputStream): Channel[Task,Array[Byte],Array[Byte]] =
    resource(Task.delay(is))(
             src => Task.delay(src.close)) { src =>
      Task.now { (buf: Array[Byte]) => Task.delay {
        val m = src.read(buf)
        if (m == buf.length) buf
        else if (m == -1) throw Cause.Terminated(Cause.End)
        else { // multiple reads for stubborn InputStreams
          @tailrec def loop(received: Int): Array[Byte] = {
            val m2 = src.read(buf, received, buf.length - received)
            if (m2 == -1) buf.take(received)
            else if (m2 + received == buf.length) buf
            else loop(m2 + received)
          }
          loop(m)
        }
      }}
    }

  /**
   * Converts a source to a mutable `InputStream`.  The resulting input stream
   * should be reasonably efficient and supports early termination (i.e. all
   * finalizers associated with the input process will be run if the stream is
   * closed).
   */
  def toInputStream(p: Process[Task, ByteVector]): InputStream = new InputStream {
    import Cause.{EarlyCause, End, Kill}

    var cur = p

    var index = 0
    var chunks: Seq[ByteVector] = Nil    // we only consider the head to be valid at any point in time

    def read(): Int = {
      if (cur.isHalt && chunks.isEmpty) {
        -1
      } else {
        val buffer = new Array[Byte](1)
        val bytesRead = read(buffer)
        if (bytesRead == -1) {
          -1
        } else {
          buffer(0) & 0xff
        }
      }
    }

    override def read(buffer: Array[Byte], offset: Int, length: Int): Int = {
      if (cur.isHalt && chunks.isEmpty) {
        -1
      } else {
        // when our index walks off the end of our last chunk, we need to Nil it out!
        if (chunks.isEmpty) {
          step()
          read(buffer, offset, length)
        } else {
          @tailrec
          def go(offset: Int, length: Int, read: Int): Int = {
            if (chunks.isEmpty) {
              // we already took care of the "halted at start" stillborn case, so we can safely just step
              step()

              if (cur.isHalt && chunks.isEmpty)
                read         // whoops! we walked off the end of the stream and we're done
              else
                go(offset, length, read)
            } else {
              val chunk = chunks.head
              val remaining = chunk.length.toInt - index

              if (length <= remaining) {
                chunk.copyToArray(buffer, offset, index, length)

                if (length == remaining) {
                  index = 0
                  chunks = chunks.tail
                } else {
                  index += length
                }

                length + read
              } else {
                chunk.copyToArray(buffer, offset, index, remaining)

                chunks = chunks.tail
                go(offset + remaining, length - remaining, read + remaining)
              }
            }
          }

          go(offset, length, 0)
        }
      }
    }

    @tailrec
    override def close() {
      if (cur.isHalt && chunks.isEmpty) {
        chunks = Nil
      } else {
        cur = cur.kill
        cur.step match {
          case Halt(End | Kill) =>
            chunks = Nil

          case Halt(Cause.Error(e: IOException)) => throw e
          case Halt(Cause.Error(e: Exception)) => throw new IOException(e)

          // rethrow halting errors
          case Halt(Cause.Error(e)) => throw e

          case Step(Emit(_), cont) =>
            cur = cont.continue
            close()

          case Step(await: Await[Task,_,ByteVector], cont) => { // todo: ??? Cleanup
            // yay! run the Task
            cur = Util.Try(await.evaluate.run) +: cont
            close()
          }
        }
      }
    }

    @tailrec
    def step(): Unit = {
      index = 0
      cur.step match {
        case h @ Halt(End | Kill) =>
          cur = h

        case h @ Halt(Cause.Error(e: IOException)) => {
          cur = h
          throw e
        }

        case h @ Halt(Cause.Error(e: Exception)) => {
          cur = h
          throw new IOException(e)
        }

        // rethrow halting errors
        case h @ Halt(Cause.Error(e)) => {
          cur = h
          throw e
        }

        case Step(Emit(as), cont) => {
          chunks = as
          cur = cont.continue
        }

        case Step(Await(request, receive,_), cont) => { // todo: ??? Cleanup
          // yay! run the Task
          cur = Util.Try(receive(EarlyCause.fromTaskResult(request.attempt.run)).run) +: cont
          step()    // push things onto the stack and then step further (tail recursively)
        }
      }
    }
  }
}
