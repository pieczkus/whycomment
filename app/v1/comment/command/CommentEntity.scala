package v1.comment.command

import akka.actor.Props
import com.trueaccord.scalapb.GeneratedMessage
import pl.why.common._
import pl.why.comment.proto.Comment
import v1.comment.command.CommentEntity.Command.{CreateComment, PublishComment}
import v1.comment.command.CommentEntity.Event.{CommentCreated, CommentPublished}

case class CommentData(uuid: String, referenceUuid: String, authorName: String, email: String, content: String,
                       createdOn: Long = System.currentTimeMillis(), published: Boolean = false, deleted: Boolean = false)
  extends EntityFieldsObject[String, CommentData] {

  override def assignId(id: String): CommentData = copy(uuid = id)

  override def id: String = uuid

  override def markDeleted: CommentData = copy(deleted = true)
}

object CommentData {
  lazy val empty = CommentData("", "", "", "", "", 0L)
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
          Some(Comment.Comment(c.uuid, c.referenceUuid, c.authorName, c.email, c.content, c.createdOn, c.published, c.deleted)))
      }
    }

    object CommentCreated extends DataModelReader {
      def fromDataModel: PartialFunction[GeneratedMessage, CommentCreated] = {
        case cc: Comment.CommentCreated =>
          val c = cc.comment.get
          CommentCreated(CommentData(c.uuid, c.referenceUuid, c.authorName, c.email, c.content, c.createdOn, c.published, c.deleted))
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
