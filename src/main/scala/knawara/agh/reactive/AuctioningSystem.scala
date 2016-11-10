package knawara.agh.reactive

import akka.actor._

import scala.concurrent.duration.FiniteDuration
import scala.util.Random

/* todo
- buyer - bid on list of auctions
- send back current price
- auction duration
- highest bidder
- handle bidder destruction
 */

case class BidTick()
case class PlaceBid(val price: Long)
case class BidTooSmall()
case object Relist

class AuctioningSystem extends Actor {
  val auction = context.actorOf(Props[Auction])
  val buyer = context.actorOf(Buyer.props(auction))

  override def receive = {
    case _ @ msg => println(s"AuctionSystem got new message: ${msg.toString}")
  }
}

object Buyer {
  def props(auction: ActorRef): Props = Props(new Buyer(auction))

  private def scheduleBidTick(target: ActorRef) = {
    import Bootstrapper.asystem.dispatcher
    import scala.concurrent.duration._

    Bootstrapper.asystem.scheduler.schedule(1 second, 500 millis , target, BidTick())
  }
}

class Buyer(auction: ActorRef) extends Actor {
  Buyer.scheduleBidTick(self)

  override def receive = {
    case BidTick() => auction ! PlaceBid(Random.nextInt(100000))
    case BidTooSmall() => println("Bid was too small")
    case _ @ msg => println(s"Buyer got new message: ${msg.toString}")
  }
}

case object AuctionEnded
case object DeleteAuction

object Auction {
  private val DELETE_TIMEOUT = 5

  private def startTimer(delay: Int, target: ActorRef, message: Any) = {
    import Bootstrapper.asystem.dispatcher
    import scala.concurrent.duration._

    Bootstrapper.asystem.scheduler.scheduleOnce(delay seconds, target, message)
  }

  private def startBidTimer(auctionDuration: Int, target: ActorRef): Cancellable =
    startTimer(auctionDuration, target, AuctionEnded)

  private def startDeleteTimer(target: ActorRef): Cancellable =
    startTimer(DELETE_TIMEOUT, target, DeleteAuction)
}

class Auction extends Actor {
  val AUCTION_DURATION = 5

  var price = 0L
  var highestBidder: Option[ActorRef] = None

  Auction.startBidTimer(AUCTION_DURATION, self)

  override def receive: Actor.Receive = receiveWhenActivated

  def receiveWhenCreated: Actor.Receive = {
    case PlaceBid(offeredPrice) =>
      price = offeredPrice
      highestBidder = Some(sender())
      context.become(receiveWhenActivated, discardOld = true)
    case AuctionEnded =>
      println("Auction ended without winner")
      Auction.startDeleteTimer(self)
      context.become(receiveWhenIgnored, discardOld = true)
    case _ @ msg => println(s"Auction got new message: ${msg.toString}")
  }

  def receiveWhenActivated: Actor.Receive = {
    case PlaceBid(offeredPrice) =>
      println(s"Bid received: $offeredPrice")
      if(offeredPrice > price) {
        price = offeredPrice
        highestBidder = Some(sender())
      }
      else sender() ! BidTooSmall()
    case _ @ msg => println(s"Auction got new message: ${msg.toString}")
  }

  def receiveWhenIgnored: Actor.Receive = {
    case DeleteAuction =>
      println("Deleting auciton")
      context.stop(self)
    case Relist =>
      Auction.startBidTimer(AUCTION_DURATION, self)
      context.become(receiveWhenCreated, discardOld = true)
    case _ @ msg => println(s"Auction got new message: ${msg.toString}")
  }
}

object Bootstrapper {
  val asystem = ActorSystem("AuctioningSystem")

  private def initializeActorSystem() = {
    asystem.actorOf(Props[AuctioningSystem])
  }

  def main(args: Array[String]): Unit = {
    initializeActorSystem()
  }
}