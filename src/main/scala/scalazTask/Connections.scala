package scalazTask

import scalaz.concurrent.Task
import scalaz.stream._
import Process._
import scala.util.Random._

object Connections {

  case class Connection(i: Int)

  val init: Process[Task, Nothing] = await(Task.delay{ println("Connection pool init'd") })(_ => halt)
  val cleanup: Process[Task, Connection] = await(Task.delay{ println("Connection pool released") })(_ => halt)

  val connections: Process[Task, Connection] = init ++ unfold(1)(i => Some(i, i+1)).map(Connection(_)) onComplete{ cleanup }
  val fiveAttempts = connections.take(5)

  val console: Sink[Task, String] = constant((s: String) => Task.delay{ println(s) })

  class Data {
    val n = 20
    var contents: List[String] = List.fill(n)(nextString(10)).zipWithIndex.map{ case (e, idx) => s"Data ${idx+1}/$n: $e" }
    val initialSize = contents.size
    def take(): String = contents match {
      case h :: t => { contents = t; h }
      case Nil => { contents = Nil; throw new RuntimeException("data exhausted") }
    }

    def report: String = s"Data: initial [$initialSize]; remaining [${contents.size}]"
  }

  def readData(data: Data, conn: Connection): Process[Task, String] = eval(Task.delay{ data.take() }).map(s => s"$conn: $s").repeat.onFailure(_ => halt)

  val boom: Process[Task, Nothing] = fail(new RuntimeException("simulated connection failure!"))

  val reportFailureAndHalt: Throwable => Process[Task, Nothing] = { thr => await(Task.delay{ println(s"read failed with [$thr]") })(_ => halt) }
}

import Connections._

object Attempt0 extends App { // an infinite amount of data, bitten off in random-sized pieces

  def readSomeData(con: Connection): Process[Task, String] = {
    val numLinesBeforeFail = nextInt(5)
    fill(numLinesBeforeFail)(s"$con: ${nextString(10)}") ++ boom
  }

  val results = fiveAttempts flatMap { con => readSomeData(con) }

  val log = (results observe console).runLog.run
  //  println(log)
}

object Attempt1 extends App { // same as above, but with fixed amount of data
  val data = new Data

  def readSomeData(conn: Connection): Process[Task, String] = {
    val numLinesBeforeFail = nextInt(5)
    readData(data, conn).take(numLinesBeforeFail) ++ boom
  }

  val results = fiveAttempts flatMap { con => readSomeData(con) }

  val log = (results observe console).runLog.run
  //  println(log)
}

object Attempt2 extends App { // same as above with better failure handling on reading data,
  // allowing progress to the next connection
  val data = new Data

  def readSomeData(conn: Connection): Process[Task, String] = {
    val numLinesBeforeFail = nextInt(5)
    readData(data, conn).take(numLinesBeforeFail) ++ boom onFailure reportFailureAndHalt
  }

  val results = fiveAttempts flatMap { con => readSomeData(con) }

  val log = (results observe console).runLog.run
  println(data.report)
}

object Attempt3 extends App { // demo of what happens when you have enough conns to exhaust the whole data stream
  val data = new Data

  def readSomeData(conn: Connection): Process[Task, String] = {
    val numLinesBeforeFail = nextInt(5)
    readData(data, conn).take(numLinesBeforeFail) ++ boom onFailure reportFailureAndHalt
  }

  val results = connections.take(20) flatMap { con => readSomeData(con) }

  val log = (results observe console).runLog.run
  println(data.report)
}

object Attempt4 extends App { // explicitly catch the exhaustion of data and kill the rest of the process
  val data = new Data

  def readSomeData(conn: Connection): Process[Task, String] = {
    val numLinesBeforeFail = nextInt(5)
    val killWhenComplete = readData(data, conn) ++ await(Task.delay{ println("Completed reading all data") })(_ => Process(()).kill)
    killWhenComplete.take(numLinesBeforeFail) ++ boom onFailure reportFailureAndHalt
  }

  val results = connections.take(20) flatMap { con => readSomeData(con) }

  val log = (results observe console).runLog.run
  println(data.report)
}

// TODO:
//   use Writer to carry output instead of println?