package v1.comment.command

import akka.actor.{ActorRef, FSM, Props}
import com.trueaccord.scalapb.GeneratedMessage
import pl.why.common._
import pl.why.comment.proto.Comment
import pl.why.common.lookup.{ServiceConsumer, ServiceLookupResult}
import v1.comment.command.CommentCreateValidator._
import v1.comment.command.CommentEntity.Command.{CreateComment, PublishComment}
import v1.comment.command.CommentEntity.Event.{CommentCreated, CommentPublished}
import v1.comment.query.CommentJsonProtocol

import scala.concurrent.duration._

case class CommentData(uuid: String, referenceUuid: String, referenceType: String, authorName: String, email: String, content: String,
                       createdOn: Long = System.currentTimeMillis(), published: Boolean = false, deleted: Boolean = false)
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

    case class PublishComment(commentUuid: String) extends EntityCommand {
      def entityId: String = commentUuid
    }

  }

  object Event {

    trait CommentEvent extends EntityEvent {
      def entityType = EntityType
    }

    case class CommentCreated(c: CommentData) extends CommentEvent {
      override def toDataModel: Comment.CommentCreated = {
        Comment.CommentCreated(
          Some(Comment.Comment(c.uuid, c.referenceUuid, c.referenceType, c.authorName, c.email, c.content, c.createdOn, c.published, c.deleted)))
      }
    }

    object CommentCreated extends DataModelReader {
      def fromDataModel: PartialFunction[GeneratedMessage, CommentCreated] = {
        case cc: Comment.CommentCreated =>
          val c = cc.comment.get
          CommentCreated(CommentData(c.uuid, c.referenceUuid, c.referenceType, c.authorName, c.email, c.content, c.createdOn, c.published, c.deleted))
      }
    }

    case class CommentPublished(c: String) extends CommentEvent {
      override def toDataModel: Comment.CommentPublished = {
        Comment.CommentPublished(c)
      }
    }

    object CommentPublished extends DataModelReader {
      def fromDataModel: PartialFunction[GeneratedMessage, CommentPublished] = {
        case c: Comment.CommentPublished =>
          CommentPublished(c.uuid)
      }
    }

  }

}

class CommentEntity extends PersistentEntity[CommentData] {

  override def additionalCommandHandling: Receive = {
    case CreateComment(c) =>
      persist(CommentCreated(c)) {
        handleEventAndRespond()
      }

    case PublishComment(uuid) =>
      persist(CommentPublished(uuid)) {
        handleEventAndRespond()
      }
  }

  override def isCreateMessage(cmd: Any): Boolean = cmd match {
    case CreateComment(_) => true
    case _ => false
  }

  override def initialState: CommentData = CommentData.empty

  override def handleEvent(event: EntityEvent): Unit = event match {
    case CommentCreated(c) =>
      state = c

    case CommentPublished(_) =>
      state = state.copy(published = true)
  }
}

private[comment] object CommentCreateValidator {

  def props = Props[CommentCreateValidator]

  sealed trait State
  case object WaitingForRequest extends State
  case object ResolvingDependencies extends State
  case object LookingUpEntities extends State

  sealed trait Data{
    def inputs:Inputs
  }
  case object NoData extends Data{
    def inputs = Inputs(ActorRef.noSender, null)
  }
  case class Inputs(originator:ActorRef, request: CommentEntity.Command.CreateComment)

  trait InputsData extends Data{
    def inputs:Inputs
    def originator = inputs.originator
  }
  case class UnresolvedDependencies(inputs:Inputs, referenceUri:Option[String] = None) extends InputsData

  case class ResolvedDependencies[T](inputs:Inputs, referenced:Option[T],  referenceUri:String) extends InputsData

  case class LookedUpData[T](inputs:Inputs, referenced:T) extends InputsData

  val ResolveTimeout = 5.seconds

  val InvalidReferenceIdError = ErrorMessage("invalid.referenceId", Some("You have supplied an invalid reference id"))

}

private[comment] class CommentCreateValidator extends CommonActor with FSM[CommentCreateValidator.State, CommentCreateValidator.Data]
  with ServiceConsumer with CommentJsonProtocol {

  import context.dispatcher
  import akka.pattern.pipe

  startWith(WaitingForRequest, NoData)

  when(WaitingForRequest){
    case Event(request:CreateComment, _) =>
      lookupService(request.comment.referenceType).pipeTo(self)
      goto(ResolvingDependencies) using UnresolvedDependencies(Inputs(sender(), request))
  }

  when(ResolvingDependencies, ResolveTimeout )(transform {
    case Event(ServiceLookupResult(name, uriOpt), data:UnresolvedDependencies) =>

      log.info("Resolved dependency {} to: {}", name, uriOpt)
      val newData = data.copy(referenceUri = uriOpt)
      stay using newData
  } using{
    case FSM.State(state, UnresolvedDependencies(inputs, Some(userUri),
    Some(inventoryUri), Some(creditUri)), _, _, _) =>

      log.info("Resolved all dependencies, looking up entities")
      findUserByEmail(userUri, inputs.request.userEmail).pipeTo(self)

      val expectedBooks = inputs.request.lineItems.map(_.bookId).toSet
      val bookFutures = expectedBooks.map(id => findBook(inventoryUri, id))
      bookFutures.foreach(_.pipeTo(self))
      goto(LookingUpEntities) using ResolvedDependencies(inputs, expectedBooks, None, Map.empty, inventoryUri, userUri, creditUri)
  })

}