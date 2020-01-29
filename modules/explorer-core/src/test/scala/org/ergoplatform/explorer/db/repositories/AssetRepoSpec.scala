package org.ergoplatform.explorer.db.repositories

import cats.effect.Sync
import doobie.ConnectionIO
import org.ergoplatform.explorer.{db, BoxId}
import org.ergoplatform.explorer.db.{repositories, RealDbTest}
import org.ergoplatform.explorer.db.algebra.LiftConnectionIO
import org.ergoplatform.explorer.db.models.aggregates.ExtendedOutput
import org.ergoplatform.explorer.db.syntax.runConnectionIO._
import org.scalatest.{Matchers, PropSpec}
import org.scalatestplus.scalacheck.ScalaCheckDrivenPropertyChecks

class AssetRepoSpec
  extends PropSpec
  with Matchers
  with RealDbTest
  with ScalaCheckDrivenPropertyChecks {

  import org.ergoplatform.explorer.commonGenerators._
  import org.ergoplatform.explorer.db.models.generators._

  property("insert/getAllByBoxId") {
    withLiveRepo[ConnectionIO] { repo =>
      forSingleInstance(assetsWithBoxIdGen) {
        case (boxId, assets) =>
          repo.getAllByBoxId(boxId).runWithIO() shouldBe 'empty
          assets.foreach { asset =>
            repo.insert(asset).runWithIO()
          }
          repo
            .getAllByBoxId(boxId)
            .runWithIO() should contain theSameElementsAs assets
      }
    }
  }

  property("insert/getIssuingBoxes") {
    withLiveRepos[ConnectionIO] { (assetRepo, outputRepo, inputRepo) =>
      forSingleInstance(issueTokenGen) {
        case (input, out, asset) =>
          assetRepo.getAllIssuingBoxes.compile.toList.runWithIO() shouldBe empty

          // issue a token
          inputRepo.insert(input).runWithIO()
          assetRepo.insert(asset).runWithIO()
          outputRepo.insert(out).runWithIO()
          // use issued token in new output
          val outUsingToken = outputGen(true).sample.get
          assetRepo.insert(asset.copy(boxId = outUsingToken.boxId)).runWithIO()
          outputRepo.insert(outUsingToken).runWithIO()

          assetRepo.getAllIssuingBoxes.compile.toList.runWithIO() should
          contain theSameElementsAs List(ExtendedOutput(out, None))
      }
    }
  }

  private def withLiveRepo[D[_]: LiftConnectionIO: Sync](
    body: AssetRepo[D, fs2.Stream] => Any
  ): Any =
    body(db.repositories.AssetRepo[D])

  private def withLiveRepos[D[_]: LiftConnectionIO: Sync](
    body: (AssetRepo[D, fs2.Stream], OutputRepo[D, fs2.Stream], InputRepo[D]) => Any
  ): Any =
    body(
      db.repositories.AssetRepo[D],
      db.repositories.OutputRepo[D],
      db.repositories.InputRepo[D]
    )

}
