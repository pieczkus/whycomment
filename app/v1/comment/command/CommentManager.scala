package v1.comment.command

import java.util.UUID

import akka.actor.Props
import pl.why.common.Aggregate
import v1.comment.CommentInput
import v1.comment.command.CommentEntity.Command.{CreateComment, PublishComment}
import v1.comment.command.CommentManager.{AcceptComment, AddComment}

object CommentManager {
  val Name = "comment-manager"

  case class AddComment(comment: CommentInput)

  case class AcceptComment(commentUuid: String)

  def props: Props = Props[CommentManager]
}

class CommentManager extends Aggregate[CommentData, CommentEntity] {

  override def entityProps: Props = CommentEntity.props

  override def receive: Receive = {
    case AddComment(input) =>
      val uuid = UUID.randomUUID().toString
      val comment = CommentData(uuid, input.referenceUuid, input.authorName, input.email, input.content)
      forwardCommand(uuid, CreateComment(comment))

    case AcceptComment(commentUuid) =>
      forwardCommand(commentUuid, PublishComment(commentUuid))

  }
}
