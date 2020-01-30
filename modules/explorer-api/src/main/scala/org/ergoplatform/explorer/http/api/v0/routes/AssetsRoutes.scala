package org.ergoplatform.explorer.http.api.v0.routes

import cats.data.NonEmptyList
import cats.effect.{ContextShift, Sync}
import cats.syntax.semigroupk._
import org.ergoplatform.explorer.http.api.syntax.applicativeThrow._
import org.ergoplatform.explorer.http.api.v0.services.AssetsService
import org.http4s.HttpRoutes
import sttp.tapir.server.http4s._

final class AssetsRoutes[F[_]: Sync: ContextShift](
  service: AssetsService[F, fs2.Stream]
) {

  import org.ergoplatform.explorer.http.api.v0.defs.AssetsEndpointDefs._

  val routes: HttpRoutes[F] =
    getAllIssuingBoxesR <+> getIssuingBoxR

  private def getAllIssuingBoxesR: HttpRoutes[F] =
    getAllIssuingBoxesDef.toRoutes { _ =>
      service.getAllIssuingBoxes.compile.toList.either
    }

  private def getIssuingBoxR: HttpRoutes[F] =
    getIssuingBoxDef.toRoutes { tokenId =>
      service.getIssuingBoxes(NonEmptyList.one(tokenId)).compile.toList.either
    }
}

object AssetsRoutes {

  def apply[F[_]: Sync: ContextShift](
    service: AssetsService[F, fs2.Stream]
  ): HttpRoutes[F] =
    new AssetsRoutes(service).routes
}
