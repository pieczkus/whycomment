package v1.comment.command

import akka.actor.{ActorRef, FSM, Props}
import akka.http.scaladsl.model.{HttpMethods, HttpRequest, Uri}
import com.trueaccord.scalapb.GeneratedMessage
import pl.why.common._
import pl.why.comment.proto.Comment
import pl.why.common.lookup.{ApiResponseJsonProtocol, ServiceConsumer, SimpleResponse}
import v1.comment.command.CommentEntity.Command.{CreateComment, CreateValidateComment}
import v1.comment.command.CommentEntity.Event.CommentCreated

import scala.concurrent.Future

case class CommentData(uuid: String, referenceUuid: String, key: String, authorName: String, email: String, content: String,
                       createdOn: Long = System.currentTimeMillis(), deleted: Boolean = false)
  extends EntityFieldsObject[String, CommentData] {

  override def assignId(id: String): CommentData = copy(uuid = id)

  override def id: String = uuid

  override def markDeleted: CommentData = copy(deleted = true)
}

object CommentData {
  lazy val empty = CommentData("", "", "", "", "", "", 0L)
}

object CommentEntity {

  val EntityType = "comment"

  def props: Props = Props[CommentEntity]

  object Command {

    case class CreateComment(comment: CommentData) extends EntityCommand {
      def entityId: String = comment.uuid
    }

    case class CreateValidateComment(comment: CommentData) extends EntityCommand {
      def entityId: String = comment.uuid
    }

  }

  object Event {

    trait CommentEvent extends EntityEvent {
      def entityType = EntityType
    }

    case class CommentCreated(c: CommentData) extends CommentEvent {
      override def toDataModel: Comment.CommentCreated = {
        Comment.CommentCreated(
          Some(Comment.Comment(c.uuid, c.referenceUuid, c.key, c.authorName, c.email, c.content, c.createdOn, c.deleted)))
      }
    }

    object CommentCreated extends DataModelReader {
      def fromDataModel: PartialFunction[GeneratedMessage, CommentCreated] = {
        case cc: Comment.CommentCreated =>
          val c = cc.comment.get
          CommentCreated(CommentData(c.uuid, c.referenceUuid, c.key, c.authorName, c.email, c.content, c.createdOn, c.deleted))
      }
    }

    case class ValidatedCommentCreated(c: CommentData) extends CommentEvent {
      override def toDataModel: Comment.ValidatedCommentCreated = {
        Comment.ValidatedCommentCreated(
          Some(Comment.Comment(c.uuid, c.referenceUuid, c.key, c.authorName, c.email, c.content, c.createdOn, c.deleted)))
      }
    }

    object ValidatedCommentCreated extends DataModelReader {
      def fromDataModel: PartialFunction[GeneratedMessage, ValidatedCommentCreated] = {
        case cc: Comment.ValidatedCommentCreated =>
          val c = cc.comment.get
          ValidatedCommentCreated(CommentData(c.uuid, c.referenceUuid, c.key, c.authorName, c.email, c.content, c.createdOn, c.deleted))
      }
    }

  }

}

class CommentEntity extends PersistentEntity[CommentData] {

  override def additionalCommandHandling: Receive = {
    case cc: CreateComment =>
      val validator = context.actorOf(CommentValidator.props)
      validator.forward(cc)

    case CreateValidateComment(c) =>
      persist(CommentCreated(c)) {
        handleEventAndRespond()
      }

  }

  override def isCreateMessage(cmd: Any): Boolean = cmd match {
    case CreateComment(_) => true
    case CreateValidateComment(_) => true
    case _ => false
  }

  override def initialState: CommentData = CommentData.empty

  override def handleEvent(event: EntityEvent): Unit = event match {
    case CommentCreated(c) =>
      state = c

  }
}

private object CommentValidator {

  import CommentEntity._

  def props = Props[CommentValidator]

  sealed trait State

  case object WaitingForRequest extends State

  case object IncrementingCommentCount extends State

  case class Inputs(originator: ActorRef, request: Command.CreateComment)

  sealed trait Data {
    def inputs: Inputs
  }

  case object NoData extends Data {
    override def inputs: Inputs = Inputs(ActorRef.noSender, null)
  }

  case class FilledData(inputs: Inputs) extends Data

}

private class CommentValidator extends CommonActor with FSM[CommentValidator.State, CommentValidator.Data]
  with ServiceConsumer with ApiResponseJsonProtocol {

  import CommentEntity.Command._
  import CommentValidator._
  import akka.pattern.pipe
  import scala.concurrent.duration._
  import context.dispatcher

  startWith(WaitingForRequest, NoData)

  when(WaitingForRequest) {
    case Event(request: CreateComment, _) =>
      incrementCommentCount(request.comment.referenceUuid).pipeTo(self)
      goto(IncrementingCommentCount) using FilledData(Inputs(sender(), request))
  }

  when(IncrementingCommentCount, 3.seconds) {
    case Event(commentresponse: SimpleResponse, data: FilledData) =>
      context.parent.tell(CreateValidateComment(data.inputs.request.comment), data.inputs.originator)
      stop
  }

  whenUnhandled {
    case Event(StateTimeout, data) =>
      log.error("Received state timeout in process to validate an order create request")
      data.inputs.originator ! unexpectedFail
      stop

    case Event(other, data) =>
      log.error("Received unexpected message of {} in state {}", other, stateName)
      data.inputs.originator ! unexpectedFail
      stop
  }


  private def incrementCommentCount(id: String): Future[SimpleResponse] = {
    val postUri = context.system.settings.config.getString("services.post")
    val requestUri = Uri(postUri).withPath(Uri.Path("/v1/posts/" + id + "/comment"))
    executeHttpRequest[SimpleResponse](HttpRequest(HttpMethods.POST, requestUri))
  }

  private def unexpectedFail = Failure(FailureType.Service, ServiceResult.UnexpectedFailure)
}