package v1.comment.command

import java.util.UUID

import akka.actor.Props
import pl.why.common.Aggregate
import v1.comment.CommentInput
import v1.comment.command.CommentEntity.Command.CreateComment
import v1.comment.command.CommentManager.AddComment

object CommentManager {
  val Name = "comment-manager"

  case class AddComment(key: String, comment: CommentInput)

  def props: Props = Props[CommentManager]
}

class CommentManager extends Aggregate[CommentData, CommentEntity] {

  override def entityProps: Props = CommentEntity.props

  override def receive: Receive = {
    case AddComment(key, input) =>
      val uuid = UUID.randomUUID().toString
      val comment = CommentData(uuid, input.referenceUuid, key, input.authorName, input.email, input.content)
      forwardCommand(uuid, CreateComment(comment))

  }
}
