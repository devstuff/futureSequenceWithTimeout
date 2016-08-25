package com.devstuff.futures

import java.time.Clock
import java.time.{Duration => JDuration}
import java.time.format.DateTimeFormatter
import java.util.{Timer, TimerTask}

import akka.actor.ActorSystem
import akka.pattern.after

import scala.concurrent.duration._
import scala.concurrent._
import scala.concurrent.ExecutionContext.Implicits.global
import scala.util.{Success, Try}
import scala.languageFeature.postfixOps
import scala.util.control.NonFatal

object Start {

  val clock = Clock.systemUTC
  val dateTimeFormatter = DateTimeFormatter.ISO_INSTANT

  def trace(message: String): Unit = {
    println(s"${dateTimeFormatter.format(clock.instant)}: ${message}")
  }

  def timer[T](message: String)(block: => T): T = {
    val start = clock.instant
    println(s"${dateTimeFormatter.format(start)}: ${message} - start")
    trace(s"${message} - start")
    val result = block
    val end = clock.instant
    val delta = JDuration.between(start, end).toMillis
    println(s"${dateTimeFormatter.format(end)}: ${message} - end (${delta}ms)")
    result
  }

  def main(args: Array[String]): Unit = {
    main2()
  }

//  def main1(): Unit = {
//    val system = ActorSystem("main1")
//    val delayed = after(1200.millis, using = system.scheduler)(Future.failed(new IllegalStateException("OHNOES")))
//    val future = Future { Thread.sleep(1000); "foo" }
//    val result = Future firstCompletedOf Seq(future, delayed)
//    val safe = result.recover {
//      case ex: IllegalStateException => "bar"
//    }
//    timer("Await") {
//      val x = Await.result(safe, Duration(2000, MILLISECONDS))
//      trace(x)
//    }
//    system.shutdown()
//  }

  // blocking() around Thread.sleep() fixes scheduling issues (e.g. #11 would timeout incorrectly).
  def sleepyFuture[T](timeoutMillis: Int, completedValue: T, timeoutValue: T, exceptionValue: T, delayTrigger: Future[T]): Future[T] = {
    val f = Future {
      trace(s"Starting ${completedValue}")
      if (timeoutMillis == 3000) {
        blocking(Thread.sleep(timeoutMillis / 2))
        throw new IllegalArgumentException("something went wrong")
      } else {
        blocking(Thread.sleep(timeoutMillis))
        trace(s"Complete: ${completedValue}")
        completedValue
      }
    }
    val firstResult = Future.firstCompletedOf(Seq(f, delayTrigger))
    val safeResult = firstResult.recover {
      case tex: TimeoutException => {
        trace(s"Timeout: ${timeoutValue}")
        // An interruptable future would be cancelled here; something like: f.cancel()
        timeoutValue
      }
      case NonFatal(nf) => {
        trace(s"Exception: ${exceptionValue}")
        exceptionValue
      }
    }
    safeResult
  }

  // This one finally works (yay!), output should be similar to this:
  //
  //  2016-08-24T18:30:03.691Z: Starting 1-good
  //  2016-08-24T18:30:03.692Z: Starting 2-good
  //  2016-08-24T18:30:03.692Z: Starting 4-good
  //  2016-08-24T18:30:03.693Z: Starting 3-good
  //  2016-08-24T18:30:03.693Z: Starting 5-good
  //  2016-08-24T18:30:03.693Z: Starting 6-good
  //  2016-08-24T18:30:03.693Z: Starting 7-good
  //  2016-08-24T18:30:03.693Z: Starting 8-good
  //  2016-08-24T18:30:03.694Z: Starting 9-good
  //  2016-08-24T18:30:03.694Z: Await - start
  //  2016-08-24T18:30:03.694Z: Await - start
  //  2016-08-24T18:30:03.694Z: Starting 10-good
  //  2016-08-24T18:30:03.694Z: Starting 11-good
  //  2016-08-24T18:30:03.695Z: Starting 12-good
  //  2016-08-24T18:30:04.195Z: Complete: 1-good
  //  2016-08-24T18:30:04.195Z: Complete: 7-good
  //  2016-08-24T18:30:04.696Z: Complete: 2-good
  //  2016-08-24T18:30:04.699Z: Complete: 9-good
  //  2016-08-24T18:30:05.198Z: Complete: 3-good
  //  2016-08-24T18:30:05.198Z: Exception: 6-exception
  //  2016-08-24T18:30:05.199Z: Complete: 12-good
  //  2016-08-24T18:30:05.695Z: Complete: 4-good
  //  2016-08-24T18:30:05.697Z: Complete: 8-good
  //  2016-08-24T18:30:06.197Z: Complete: 5-good
  //  2016-08-24T18:30:06.197Z: Complete: 11-good
  //  2016-08-24T18:30:06.806Z: Timeout: 10-timeout
  //  2016-08-24T18:30:06.807Z: 1-good
  //  2016-08-24T18:30:06.807Z: 2-good
  //  2016-08-24T18:30:06.807Z: 3-good
  //  2016-08-24T18:30:06.807Z: 4-good
  //  2016-08-24T18:30:06.807Z: 5-good
  //  2016-08-24T18:30:06.807Z: 6-exception
  //  2016-08-24T18:30:06.807Z: 7-good
  //  2016-08-24T18:30:06.807Z: 8-good
  //  2016-08-24T18:30:06.807Z: 9-good
  //  2016-08-24T18:30:06.807Z: 10-timeout
  //  2016-08-24T18:30:06.807Z: 11-good
  //  2016-08-24T18:30:06.807Z: 12-good
  //  2016-08-24T18:30:06.807Z: Await - end (3113ms)    <=- A smidgen/tick longer than the specified timeout period
  //  2016-08-24T17:50:03.762Z: Complete: 10-good       <=- Spurious output. #10 has already timed out, but this occurs
  //                                                        after Thread.sleep unblocks.
  //
  // I think I can ignore the spurious completion for my use case (availability status queries), since it doesn't affect
  // the overall result. But if the spurious future has side effects that shouldn't occur after a timeout, then I may
  // need to interrupt the original future inside the recover block.
  //    ref: https://gist.github.com/viktorklang/5409467
  //    via: https://stackoverflow.com/questions/16009837/how-to-cancel-future-in-scala
  //
  def main2(): Unit = {
    val maxItemWaitTime = 3100.millis
    val maxOverallWaitTime = 4600.millis
    val listItems = List(1, 2, 3, 4, 5, 6, 1, 4, 2, 7, 5, 3)
    val listZip = listItems.zipWithIndex
    val system = ActorSystem("main2")
    val delayed = akka.pattern.after(maxItemWaitTime, using = system.scheduler)(Future.failed(new TimeoutException))
    val futures = listZip.map { t =>
      val delayMillis = t._1 * 500
      val index = t._2 + 1 // 1-based
      sleepyFuture(delayMillis, s"${index}-good", s"${index}-timeout", s"${index}-exception", delayed)
    }
    val seq = Future.sequence(futures)
    try {
      timer("Await") {
        val x = Await.result(seq, maxOverallWaitTime)
        x.foreach(y => trace(y))
      }
    } catch {
      case tex: TimeoutException => trace("Timed out waiting for results.")
    }
    Thread.sleep(500) // simulate other things before shutdown
    system.shutdown()
  }

//  def main3(): Unit = {
//    timer("Main") {
//      val maxItemWaitTime = 3000
//      val maxOverallWaitTime = 4200
//
//      val workFuture = timer("Work") {
//        val id = 42
//        val f = doWork(id, 4000)
//        futureWithTimeout(f, maxItemWaitTime, s"Timeout for #${id}")
//      }
//
//      val workCompletedFuture = workFuture
//
//      try {
//        val result = timer("Await") {
//          Await.result(workCompletedFuture, Duration(maxOverallWaitTime, MILLISECONDS))
//        }
//        trace(s"result => ${result}")
//      } catch {
//        case tex: TimeoutException => trace("Timed out waiting for results.")
//      }
//
////      val workItems = List(1000, 2000, 3000, 4000, 5000, 6000)
////      val scheduledWorkFutures = timer("Map") {
////        workItems.par.map { item =>
////          val id = item / 100
////          val f = doWork(id, item).recover {
////            case ex: Exception => s"Exception: ${ex.getMessage}"
////          }
////          FutureHelper.withTimeout(f, maxItemWaitTime, s"Timeout for #${id}")
////        }
////      }
//
////      val workCompletedFuture = timer("Sequence") {
////        Future.sequence(scheduledWorkFutures)
////      }
//
//      //    trace("Traverse starting")
//      //    val workCompletedFuture = Future.traverse(workItems)(item => {
//      //      val id = item / 100
//      //      val f = doWork(id, item).recover {
//      //        case ex: Exception => s"Exception: ${ex.getMessage}"
//      //      }
//      //      FutureHelper.withTimeout(f, maxItemWaitTime, s"Timeout for #${id}")
//      //    })
//      //    trace("Traverse complete")
//
////      try {
////        val result = timer("Await") {
////          Await.result(workCompletedFuture, Duration(maxOverallWaitTime, MILLISECONDS))
////        }
////        result.foreach(r => trace(s"result => ${r}"))
////      } catch {
////        case tex: TimeoutException => trace("Timed out waiting for results.")
////      }
//
//      //    workCompletedFuture.onSuccess {
//      //      case (w) => w.foreach(r => trace(r))
//      //    }
//    }
//  }
//
//  def doWork(id: Int, runTime: Int): Future[String] = {
//    val startTime = clock.millis
//    timer(s"Work #${id}") {
//      blocking(Thread.sleep(runTime))
//    }
//    val endTime = clock.millis
//    val result = s"Completed #${id} after ${runTime}ms (actual ${endTime - startTime})"
//    trace(result)
//    Future.successful(result)
//  }
//
//  // This timer must be a daemon, otherwise the main thread will hang waiting for the thread to exit.
//  val timer = new Timer(true)
//
//  def futureWithTimeout[T](f: Future[T], timeoutMillis: Int, default: T): Future[T] = {
//    trace("Timeout added")
//    val p = Promise[T]()
//    val timeoutTask = new TimerTask {
//      def run(): Unit = {
//        trace("Timeout triggered")
//        p.tryComplete(Success(default))
//      }
//    }
//    f.onComplete { result =>
//      trace("Original future completed")
//      timeoutTask.cancel()
//      p.tryComplete(result)
//    }
//    timer.schedule(timeoutTask, timeoutMillis)
//    trace("timer scheduled")
//    p.future
//  }
}
