package controllers

import play.api.mvc.Controller
import com.cloud9ers.play2.sockjs.SockJs
import play.api.libs.iteratee.Concurrent
import play.api.libs.iteratee.Iteratee
import play.api.libs.concurrent.Promise
import scala.concurrent.Future
import play.api.libs.iteratee.Enumerator
import play.api.mvc.RequestHeader
import scala.concurrent.ExecutionContext.Implicits.global
import play.api.mvc.Handler
import play.api.mvc.Action
import play.api.mvc.WebSocket
import play.api.mvc.BodyParser
import play.api.libs.iteratee._
import play.api.mvc.Results
import play.api.mvc.Request
import play.api.mvc.AnyContent

object SockJsService2 extends Controller {
  /*
   * userHandler (user of the plugin) -> downEnumerator -> downIteratee -> sockjsClient (browser)
   * sockjsClient -> upEnumerator -> upIteratee -> userHandler
   */
  
  /**
   * The sockJs Handler that the user of the plugin will write to handle the service logic
   * It has the same interface of the websocket
   * returns (Iteratee, Enumerator):
   * Iteratee - to iterate over msgs that will be received from the sockjs client
   * Enumerator - to enumerate the msgs that will be sent to the sockjs client
   */
  def sockJsHandler = async { rh =>
    val (downEnumerator, downChannel) = Concurrent.broadcast[String]
    val upIteratee = Iteratee.foreach[String] { msg => downChannel push msg }
    Promise.pure(upIteratee, downEnumerator)
  }

  /**
   * overload to the sockJsHandler to take the url parameter
   */
  def sockJsHandler2(route: String) = sockJsHandler

  /**
   * just returns some headers
   */
  def cors(implicit req: Request[AnyContent]) = Seq(
    ACCESS_CONTROL_ALLOW_CREDENTIALS -> "true",
    ACCESS_CONTROL_ALLOW_ORIGIN -> req.headers.get("origin").map(o => if (o != "null") o else "*").getOrElse("*"))
    .union(
      (for (acrh <- req.headers.get(ACCESS_CONTROL_REQUEST_HEADERS))
        yield (ACCESS_CONTROL_ALLOW_HEADERS -> acrh)).toSeq)

  /**
   * The same as Websocket.async
   * @param f - user function that takes the request header and return Future of the user's Iteratee and Enumerator
   */
  def async[A](f: RequestHeader => Future[(Iteratee[A, _], Enumerator[A])]): Handler = {
    using { rh =>
      val p = f(rh)
      val upIteratee = Iteratee.flatten(p.map(_._1))
      val downEnumerator = Enumerator.flatten(p.map(_._2))
      (upIteratee, downEnumerator)
    }
  }

  /**
   * returns Handler and passes a function that pipes the user Enumerator to the sockjs Iteratee
   * and pipes the sockjs Enumerator to the user Iteratee
   */
  def using[A](f: RequestHeader => (Iteratee[A, _], Enumerator[A])): Handler = {
    handler { h =>
      (upEnumerator: Enumerator[A], downIteratee: Iteratee[A, Unit]) =>
        // call the user function and holds the user's Iteratee (in) and Enumerator (out)
        val (upIteratee, downEnumerator) = f(h)

        // pipes the msgs from the sockjs client to the user's Iteratee
        upEnumerator |>> upIteratee

        // pipes the msgs from the user's Enumerator to the sockjs client
        downEnumerator |>> downIteratee
    }
  }

  /**
   * Mainly passes the sockjs Enumerator/Iteratee to the function that associate them with the user's Iteratee/Enumerator respectively
   * According to the transport, it creates the sockjs Enumerator/Iteratee and return Handler in each path
   * calls enqueue/dequeue of the session to handle msg queue between send and receive
   */
  def handler[A](f: RequestHeader => (Enumerator[A], Iteratee[A, Unit]) => Unit) = {
    if (true) Action { implicit request => // Should match handler type (Actoin, Websocket .. etc)
      println(request.path)
      val Array(_, _, serverId, sessionId, transport) = request.path.split("/")
      transport match {
        case "xhr" =>
          Async(getOrCreateSession(sessionId).dequeue().map(m =>
            Ok(m.toString)
              .withHeaders(
                CONTENT_TYPE -> "application/javascript;charset=UTF-8",
                CACHE_CONTROL -> "no-store, no-cache, must-revalidate, max-age=0")
              .withHeaders(cors: _*)))
        case "xhr_send" =>
          val (upEnumerator, upChannel) = Concurrent.broadcast[A]
          val downIteratee = Iteratee.foreach[A] { userMsg =>
            getOrCreateSession(sessionId).enqueue(userMsg.asInstanceOf[String])
          }
          // calls the user function and passes the sockjs Enumerator/Iteratee
          f(request)(upEnumerator, downIteratee)
          //request.body.asText.map { m => println(m); (upChannel push m.asInstanceOf[A]) }
          //FIXME map doesn't happen, the body is not text/plain, 7asbia Allah w ne3ma el wakel :@
          upChannel push "a[\"x\"]\n".asInstanceOf[A]
          NoContent
            .withHeaders(
              CONTENT_TYPE -> "text/plain;charset=UTF-8",
              CACHE_CONTROL -> "no-store, no-cache, must-revalidate, max-age=0")
            .withHeaders(cors: _*)
      }
    }
    else {
      Action(Ok) // FIXME change to websocket
      //      WebSocket.using[A] { rh =>
      //        f(rh)(enumerator, iteratee)
      //        (iteratee, enumerator)
      //      }
    }
  }
  trait Event
  case class Msg(msg: String) extends Event
  case class ReadyToWrite(p: scala.concurrent.Promise[String]) extends Event

  /**
   * Session class to queue messages over multiple connection like xhr and xhr_send
   */
  class Session(sessionId: String) {
    // for queuing the messages to be flushed when the downstream connection is ready 
    private[this] val (msgEnumerator, msgChannel) = Concurrent.broadcast[Event]
    // iterate over the msgEnumertor and keep the context/state of the msg queue
    def msgIteratee: Iteratee[Event, String] = {
      def step(m: Event, ms: String)(i: Input[Event]): Iteratee[Event, String] = i match {
        case Input.EOF | Input.Empty => Done(ms, Input.EOF)
        case Input.El(e) => e match {
          // send the msg to the next step or flush if the downstream connection is ready
          case Msg(msg) =>
            println(msg)
            Cont(i => step(Msg(msg), ms + msg)(i)) //TODO passes a well formed json array instead of just concat
          // takes a promise and fulfill that promise if the msg queue has items or send ready state to the next step otherwise
          case ReadyToWrite(p) => //TODO check if the ms is not empty and send the p to next step
            p success ms
            Cont(i => step(Msg(""), "")(i))
        }
      }
      Cont(i => step(Msg(""), "")(i))
    }
    // run the msgIteratee over the msgEnumerator
    msgEnumerator run msgIteratee

    /**
     * returns a Future of the messages queue that should be sent to the sockjs client in one string
     * clear the message queue if the downstream connection is ready to write
     */
    def dequeue(): Future[String] = {
      val p = Promise[String]()
      msgChannel push ReadyToWrite(p) // send ReadyToWrite to the msgIteratee with the promise that will be used to return the msgs
      p.future
    }
    /**
     * adds a message to message queue
     */
    def enqueue(msg: String) = msgChannel push Msg(msg) // pushes a message to the message queue
  }

  //TODO find a more decent way to store sessions
  val sessions = scala.collection.mutable.Map[String, Session]()
  /**
   * returns the session. If the session is not created, it creates it and send the first message: "o\n"
   */
  def getOrCreateSession(sessionId: String): Session = sessions.get(sessionId).getOrElse {
    val session = new Session(sessionId)
    sessions put (sessionId, session)
    session.enqueue("o\n")
    session
  }
}