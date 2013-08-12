package com.cloud9ers.play2.sockjs

import play.api.libs.iteratee.{ Concurrent, Input, Iteratee, Cont, Done }
import scala.concurrent.{ Promise, Future }

/**
 * Session class to queue messages over multiple connection like xhr and xhr_send
 */
class Session(sessionId: String) {
  trait Event
  case class Msg(msg: String) extends Event
  type Writer = String => Boolean
  case class ReadyToWrite(writer: Writer) extends Event
  /**
   * Structure to keep track of iteration state
   * @param ms - list of queued messages waiting to be sent to the sockjs client
   * @param writablePromise - promise will be used to send data on it's onsuccess callback
   */
  case class Accumulator(ms: List[String], writer: Option[Writer])
  // for queuing the messages to be flushed when the downstream connection is ready 
  private[this] val (msgEnumerator, msgChannel) = Concurrent.broadcast[Event]

  def encodeMsgs(ms: Seq[String]): String = ms.reduceLeft(_ + _) //TODO write the sockjs encoding function
  // iterate over the msgEnumertor and keep the context/state of the msg queue
  def msgIteratee: Iteratee[Event, Accumulator] = {
    def step(m: Event, acc: Accumulator)(implicit i: Input[Event]): Iteratee[Event, Accumulator] = i match {
      case Input.EOF | Input.Empty => Done(acc, Input.EOF)
      case Input.El(e) => e match {
        // send the msg to the next step or flush if the downstream connection is ready
        case Msg(msg) =>
          println(msg)
          acc.writer match {
            case Some(writer) => sendFrame(acc.ms :+ msg, writer) //TODO test for: check if the down channel is ready and a new msg received
            case None => Cont(i => step(Msg(msg), Accumulator(acc.ms :+ msg, None))(i)) //TODO optimize append to the right
          }
        // takes a promise and fulfill that promise if the msg queue has items or send ready state to the next step otherwise
        case ReadyToWrite(writer) => //TODO test for: check if the ms is not empty and send the p to next step
          acc.ms match {
            case Nil => Cont(i => step(Msg(""), acc)(i))
            case ms => sendFrame(ms, writer)
          }
      }
    }
    def sendFrame(ms: List[String], writer: String => Boolean)(implicit i: Input[Event]): Iteratee[Event, Accumulator] = {
      val writable = writer(encodeMsgs(ms.filterNot(_ == "")))
      Cont(i => step(Msg(""), Accumulator(Nil, if (writable) Some(writer) else None))(i))
    }
    Cont(i => step(Msg(""), Accumulator(Nil, None))(i))
  }
  // run the msgIteratee over the msgEnumerator
  msgEnumerator run msgIteratee

  /**
   * returns a Future of the messages queue that should be sent to the sockjs client in one string
   * clear the message queue if the downstream connection is ready to write
   */
  def dequeue(): Future[String] = {
    val p = Promise[String]()
    msgChannel push ReadyToWrite { ms => p success ms; false } // send ReadyToWrite to the msgIteratee with the promise that will be used to return the msgs
    p.future
  }
  /**
   * adds a message to message queue
   */
  def enqueue(msg: String) = {
    msgChannel push Msg(msg) // pushes a message to the message queue
    this
  }
  /**
   * send the queued messages using the writer function when the downstream channel is ready
   * @param writer - function takes the msg and write it according to the transport
   */
  def send(writer: String => Boolean) = {
    msgChannel push ReadyToWrite(writer)
    this
  }

}

object SessionManager {
  //TODO find a more decent way to store sessions
  val sessions = scala.collection.mutable.Map[String, Session]()
  /**
   * returns the session. If the session is not created, it creates it and send the first message: "o\n"
   */
  def getOrCreateSession(sessionId: String): Session = sessions.get(sessionId).getOrElse {
    val session = new Session(sessionId)
    sessions put (sessionId, session)
    session
      .enqueue("o\n")
    session
  }

}