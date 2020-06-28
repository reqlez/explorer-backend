package org.ergoplatform.explorer.http.api.v0.services

import cats.Monad
import cats.data.{Chain, OptionT}
import cats.effect.Concurrent
import cats.instances.list._
import cats.syntax.apply._
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.list._
import cats.syntax.traverse._
import dev.profunktor.redis4cats.algebra.RedisCommands
import fs2.Stream
import io.chrisdavenport.log4cats.Logger
import io.chrisdavenport.log4cats.slf4j.Slf4jLogger
import io.estatico.newtype.ops._
import mouse.anyf._
import org.ergoplatform.explorer.Err.{RefinementFailed, RequestProcessingErr}
import org.ergoplatform.explorer._
import org.ergoplatform.explorer.cache.repositories.ErgoLikeTransactionRepo
import org.ergoplatform.explorer.db.Trans
import org.ergoplatform.explorer.db.algebra.LiftConnectionIO
import org.ergoplatform.explorer.db.models.UTransaction
import org.ergoplatform.explorer.db.repositories.{
  TransactionRepo,
  UAssetRepo,
  UInputRepo,
  UOutputRepo,
  UTransactionRepo
}
import org.ergoplatform.explorer.http.api.models.{Items, Paging}
import org.ergoplatform.explorer.http.api.v0.models.{
  TxIdResponse,
  UTransactionInfo,
  UTransactionSummary
}
import org.ergoplatform.explorer.protocol.utils
import org.ergoplatform.explorer.settings.UtxCacheSettings
import org.ergoplatform.{ErgoAddressEncoder, ErgoLikeTransaction}

/** A service providing an access to unconfirmed transactions data.
  */
trait OffChainService[F[_]] {

  /** Get unconfirmed transactions.
    */
  def getUnconfirmedTxs(paging: Paging): F[Items[UTransactionInfo]]

  /** Get unconfirmed transaction with a given `id`.
    */
  def getUnconfirmedTxInfo(id: TxId): F[Option[UTransactionSummary]]

  /** Get all unconfirmed transactions related to the given `address`.
    */
  def getUnconfirmedTxsByAddress(address: Address, paging: Paging): F[Items[UTransactionInfo]]

  /** Get all unconfirmed transactions related to the given `ergoTree`.
    */
  def getUnconfirmedTxsByErgoTree(
    ergoTree: HexString,
    paging: Paging
  ): F[Items[UTransactionInfo]]

  /** Submit a transaction to the network.
    */
  def submitTransaction(tx: ErgoLikeTransaction): F[TxIdResponse]
}

object OffChainService {

  def apply[F[_]: Concurrent, D[_]: Monad: LiftConnectionIO](
    utxCacheSettings: UtxCacheSettings,
    redis: RedisCommands[F, String, String]
  )(
    trans: D Trans F
  )(implicit e: ErgoAddressEncoder): F[OffChainService[F]] =
    Slf4jLogger.create[F].flatMap { implicit logger =>
      ErgoLikeTransactionRepo[F](utxCacheSettings, redis).flatMap { etxRepo =>
        (
          TransactionRepo[F, D],
          UTransactionRepo[F, D],
          UInputRepo[F, D],
          UOutputRepo[F, D],
          UAssetRepo[F, D]
        ).mapN(new Live(_, _, _, _, _, etxRepo)(trans))
      }
    }

  final private class Live[
    F[_]: CRaise[*[_], RequestProcessingErr]: CRaise[*[_], RefinementFailed]: Monad: Logger,
    D[_]: Monad
  ](
    txRepo: TransactionRepo[D, Stream],
    uTxRepo: UTransactionRepo[D, Stream],
    inRepo: UInputRepo[D, Stream],
    outRepo: UOutputRepo[D, Stream],
    assetRepo: UAssetRepo[D],
    ergoLikeTxRepo: ErgoLikeTransactionRepo[F, Stream]
  )(trans: D Trans F)(implicit e: ErgoAddressEncoder)
    extends OffChainService[F] {

    def getUnconfirmedTxs(paging: Paging): F[Items[UTransactionInfo]] =
      uTxRepo.countAll.flatMap { total =>
        txRepo.getRecentIds.flatMap { recentlyConfirmed =>
          uTxRepo
            .getAll(paging.offset, paging.limit)
            .map(_.grouped(100))
            .flatMap(_.toList.flatTraverse(assembleUInfo))
            .map(confirmedDiff(_, total)(recentlyConfirmed))
        }
      } ||> trans.xa

    def getUnconfirmedTxInfo(id: TxId): F[Option[UTransactionSummary]] =
      (for {
        txOpt <- uTxRepo.get(id)
        ins   <- txOpt.toList.flatTraverse(tx => inRepo.getAllByTxId(tx.id))
        outs  <- txOpt.toList.flatTraverse(tx => outRepo.getAllByTxId(tx.id))
        boxIdsNel = outs.map(_.boxId).toNel
        assets <- boxIdsNel.toList.flatTraverse(assetRepo.getAllByBoxIds)
        txInfo = txOpt.map(UTransactionSummary(_, ins, outs, assets))
      } yield txInfo) ||> trans.xa

    def getUnconfirmedTxsByAddress(
      address: Address,
      paging: Paging
    ): F[Items[UTransactionInfo]] =
      utils
        .addressToErgoTreeHex[F](address)
        .flatMap(getUnconfirmedTxsByErgoTree(_, paging))

    def getUnconfirmedTxsByErgoTree(
      ergoTree: HexString,
      paging: Paging
    ): F[Items[UTransactionInfo]] =
      uTxRepo.countByErgoTree(ergoTree).flatMap { total =>
        txRepo.getRecentIds.flatMap { recentlyConfirmed =>
          uTxRepo
            .getAllRelatedToErgoTree(ergoTree, paging.offset, paging.limit)
            .map(_.grouped(100))
            .flatMap(_.toList.flatTraverse(assembleUInfo))
            .map(confirmedDiff(_, total)(recentlyConfirmed))
        }
      } ||> trans.xa

    def submitTransaction(tx: ErgoLikeTransaction): F[TxIdResponse] =
      Logger[F].info(s"Persisting ErgoLikeTransaction with id '${tx.id}'") >>
      ergoLikeTxRepo.put(tx) as TxIdResponse(tx.id.toString.coerce[TxId])

    private def assembleUInfo: List[UTransaction] => D[List[UTransactionInfo]] =
      txChunk =>
        (for {
          txIdsNel  <- OptionT.fromOption[D](txChunk.map(_.id).toNel)
          ins       <- OptionT.liftF(inRepo.getAllByTxIds(txIdsNel))
          outs      <- OptionT.liftF(outRepo.getAllByTxIds(txIdsNel))
          boxIdsNel <- OptionT.fromOption[D](outs.map(_.boxId).toNel)
          assets    <- OptionT.liftF(assetRepo.getAllByBoxIds(boxIdsNel))
          txInfo = UTransactionInfo.batch(txChunk, ins, outs, assets)
        } yield txInfo).value.map(_.toList.flatten)

    private def confirmedDiff(
      txs: List[UTransactionInfo],
      total: Int
    )(confirmedIds: List[TxId]): Items[UTransactionInfo] = {
      val filter = confirmedIds.toSet
      val (unconfirmed, filteredQty) =
        txs.foldLeft(Chain.empty[UTransactionInfo] -> 0) {
          case ((acc, c), tx) =>
            if (filter.contains(tx.id)) acc -> (c + 1)
            else (acc :+ tx)                -> c
        }
      Items(unconfirmed.toList, total - filteredQty)
    }
  }
}
