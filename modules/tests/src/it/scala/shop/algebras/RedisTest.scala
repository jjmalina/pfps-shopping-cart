package shop.algebras

import cats.effect._
import cats.effect.concurrent.Ref
import cats.implicits._
import dev.profunktor.redis4cats.algebra.RedisCommands
import dev.profunktor.redis4cats.connection.{ RedisClient, RedisURI }
import dev.profunktor.redis4cats.domain.RedisCodec
import dev.profunktor.redis4cats.interpreter.Redis
import dev.profunktor.redis4cats.log4cats._
import io.estatico.newtype.ops._
import java.util.UUID
import org.scalacheck.Test.Parameters
import scala.concurrent.duration._
import shop.arbitraries._
import shop.config.data._
import shop.domain.auth._
import shop.domain.brand._
import shop.domain.category._
import shop.domain.cart._
import shop.domain.item._
import shop.domain.order._
import shop.logger.NoOp
import suite.PureTestSuite

class RedisTest extends PureTestSuite {

  // For it:tests, one test is more than enough
  val MaxTests: PropertyCheckConfigParam = MinSuccessful(1)

  val Exp = 30.seconds.coerce[ShoppingCartExpiration]

  val mkRedis: Resource[IO, RedisCommands[IO, String, String]] =
    for {
      uri <- Resource.liftF(RedisURI.make[IO]("redis://localhost"))
      client <- RedisClient[IO](uri)
      cmd <- Redis[IO, String, String](client, RedisCodec.Utf8, uri)
    } yield cmd

  forAll(MaxTests) { (uid: UserId, it1: Item, it2: Item, q1: Quantity, q2: Quantity) =>
    spec("Shopping Cart") {
      mkRedis.use { cmd =>
        Ref.of[IO, Map[ItemId, Item]](Map(it1.uuid -> it1, it2.uuid -> it2)).flatMap { ref =>
          val items = new TestItems(ref)
          LiveShoppingCart.make[IO](items, cmd, Exp).flatMap { c =>
            for {
              x <- c.get(uid)
              _ <- c.add(uid, it1.uuid, q1)
              _ <- c.add(uid, it2.uuid, q1)
              y <- c.get(uid)
              _ <- c.removeItem(uid, it1.uuid)
              z <- c.get(uid)
              _ <- c.update(uid, Cart(Map(it2.uuid -> q2)))
              w <- c.get(uid)
              _ <- c.delete(uid)
              v <- c.get(uid)
            } yield
              assert(
                x.items.isEmpty && y.items.size == 2 &&
                z.items.size == 1 && v.items.isEmpty &&
                w.items.headOption.fold(false)(_.quantity == q2)
              )
          }
        }
      }
    }
  }

}

protected class TestItems(ref: Ref[IO, Map[ItemId, Item]]) extends Items[IO] {
  def findAll: IO[List[Item]] =
    ref.get.map(_.values.toList)
  def findBy(brand: BrandName): IO[List[Item]] = IO.pure(List.empty)
  def findById(itemId: ItemId): IO[Option[Item]] =
    ref.get.map(_.get(itemId))
  def create(item: CreateItem): IO[Unit] =
    GenUUID[IO].make[ItemId].flatMap { id =>
      val brand    = Brand(item.brandId, "foo".coerce[BrandName])
      val category = Category(item.categoryId, "foo".coerce[CategoryName])
      val newItem  = Item(id, item.name, item.description, item.price, brand, category)
      ref.update(_.updated(id, newItem))
    }
  def update(item: UpdateItem): IO[Unit] =
    ref.update(x => x.get(item.id).fold(x)(i => x.updated(item.id, i.copy(price = item.price))))
}
