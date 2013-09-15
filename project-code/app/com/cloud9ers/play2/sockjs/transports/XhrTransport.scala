package com.cloud9ers.play2.sockjs.transports

import scala.Array.canBuildFrom
import scala.concurrent.Promise
import scala.concurrent.duration.DurationInt

import org.codehaus.jackson.JsonParseException

import com.cloud9ers.play2.sockjs.{JsonCodec, Session, SockJsFrames}

import akka.actor.{ActorRef, PoisonPill, Props, actorRef2Scala}
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import play.api.libs.iteratee.Concurrent
import play.api.mvc.{AnyContent, Request, Result}

class XhrPollingActor(promise: Promise[String], session: ActorRef) extends TransportActor(session, Transport.XHR) {
  session ! Session.Register
  def sendFrame(msg: String): Boolean = {
    promise success msg + "\n"
    false
  }
}

class XhrStreamingActor(channel: Concurrent.Channel[Array[Byte]], session: ActorRef)
  extends TransportActor(session, Transport.XHR_STREAMING) {
  override def preStart() {
    import scala.language.postfixOps
    context.system.scheduler.scheduleOnce(100 milliseconds) {
      channel push SockJsFrames.XHR_STREAM_H_BLOCK
      session ! Session.Register
    }
  }

  def sendFrame(msg: String) = {
    channel push s"$msg\n".toArray.map(_.toByte)
    true
  }
}

object XhrTransport extends TransportController {

  def xhrPolling(sessionId: String, session: ActorRef)(implicit request: Request[AnyContent]) = Async {
    val promise = Promise[String]()
    system.actorOf(Props(new XhrPollingActor(promise, session)), s"xhr-polling.$sessionId.$randomNumber")
    promise.future.map { m =>
      Ok(m.toString)
        .withHeaders(
          CONTENT_TYPE -> "application/javascript;charset=UTF-8",
          CACHE_CONTROL -> "no-store, no-cache, must-revalidate, max-age=0")
        .withHeaders(cors: _*)
    }
  }

  def xhrStreaming(sessionId: String, session: ActorRef)(implicit request: Request[AnyContent]): Result = {
    val (enum, channel) = Concurrent.broadcast[Array[Byte]]
    val xhrStreamingActor = system.actorOf(Props(new XhrStreamingActor(channel, session.asInstanceOf[ActorRef])), s"xhr-streaming.$sessionId.$randomNumber")
    (Ok.stream(enum.onDoneEnumerating { println("DDDDDDDDDSSSSSSSSSSSSSSSSSSSSS"); xhrStreamingActor ! PoisonPill }))
      .withHeaders(
        CONTENT_TYPE -> "application/javascript;charset=UTF-8",
        CACHE_CONTROL -> "no-store, no-cache, must-revalidate, max-age=0")
      .withHeaders(cors: _*)
  }

  def xhrSend(sessionId: String, session: ActorRef)(implicit request: Request[AnyContent]): Result = {
    val message: String = request.body.asRaw.flatMap(r => r.asBytes(maxLength).map(b => new String(b))).getOrElse(request.body.asText.getOrElse(""))
    if (message == "")
      InternalServerError("Payload expected.")
    else
      try {
        val contentType = request.headers.get(CONTENT_TYPE).getOrElse(Transport.CONTENT_TYPE_PLAIN) //FIXME: sometimes it's application/xml
        println(s"XHR Send -->>>>>:::: $message, decoded message: ${JsonCodec.decodeJson(message)}")
        session ! Session.Send(JsonCodec.decodeJson(message))
        NoContent
          .withHeaders(
            CONTENT_TYPE -> contentType,
            CACHE_CONTROL -> "no-store, no-cache, must-revalidate, max-age=0")
          .withHeaders(cors: _*)
      } catch {
        case e: JsonParseException => InternalServerError("Broken JSON encoding.")
      }
  }
}
