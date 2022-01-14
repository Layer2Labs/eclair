/*
 * Copyright 2021 ACINQ SAS
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package fr.acinq.eclair.channel.publish

import akka.actor.typed.eventstream.EventStream
import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import akka.actor.typed.{ActorRef, Behavior}
import fr.acinq.bitcoin.{ByteVector32, OutPoint, Satoshi, Transaction}
import fr.acinq.eclair.NodeParams
import fr.acinq.eclair.blockchain.CurrentBlockCount
import fr.acinq.eclair.blockchain.bitcoind.rpc.BitcoinCoreClient
import fr.acinq.eclair.channel.publish.TxPublisher.{TxPublishLogContext, TxRejectedReason}
import fr.acinq.eclair.channel.{TransactionConfirmed, TransactionPublished}

import scala.concurrent.ExecutionContext
import scala.util.{Failure, Success}

/**
 * This actor publishes a fully signed transaction and monitors its status.
 * It detects if the transaction is evicted from the mempool, and reports details about its status.
 */
object MempoolTxMonitor {

  // @formatter:off
  sealed trait Command
  case class Publish(replyTo: ActorRef[TxResult], tx: Transaction, input: OutPoint, desc: String, fee: Satoshi) extends Command
  private case object PublishOk extends Command
  private case class PublishFailed(reason: Throwable) extends Command
  private case class InputStatus(spentConfirmed: Boolean, spentUnconfirmed: Boolean) extends Command
  private case class CheckInputFailed(reason: Throwable) extends Command
  private case class TxConfirmations(count: Int) extends Command
  private case object TxNotFound extends Command
  private case class GetTxConfirmationsFailed(reason: Throwable) extends Command
  private case class WrappedCurrentBlockCount(currentBlockCount: Long) extends Command
  case object Stop extends Command
  // @formatter:on

  // @formatter:off
  sealed trait TxResult
  case class TxConfirmed(tx: Transaction) extends TxResult
  case class TxRejected(txid: ByteVector32, reason: TxPublisher.TxRejectedReason) extends TxResult
  // @formatter:on

  def apply(nodeParams: NodeParams, bitcoinClient: BitcoinCoreClient, loggingInfo: TxPublishLogContext): Behavior[Command] = {
    Behaviors.setup { context =>
      Behaviors.withMdc(loggingInfo.mdc()) {
        Behaviors.receiveMessagePartial {
          case cmd: Publish => new MempoolTxMonitor(nodeParams, cmd, bitcoinClient, loggingInfo, context).publish()
          case Stop => Behaviors.stopped
        }
      }
    }
  }

}

private class MempoolTxMonitor(nodeParams: NodeParams, cmd: MempoolTxMonitor.Publish, bitcoinClient: BitcoinCoreClient, loggingInfo: TxPublishLogContext, context: ActorContext[MempoolTxMonitor.Command])(implicit ec: ExecutionContext = ExecutionContext.Implicits.global) {

  import MempoolTxMonitor._

  private val log = context.log

  def publish(): Behavior[Command] = {
    context.pipeToSelf(bitcoinClient.publishTransaction(cmd.tx)) {
      case Success(_) => PublishOk
      case Failure(reason) => PublishFailed(reason)
    }
    Behaviors.receiveMessagePartial {
      case PublishOk =>
        log.debug("txid={} was successfully published, waiting for confirmation...", cmd.tx.txid)
        context.system.eventStream ! EventStream.Publish(TransactionPublished(loggingInfo.channelId_opt.getOrElse(ByteVector32.Zeroes), loggingInfo.remoteNodeId, cmd.tx, cmd.fee, cmd.desc))
        waitForConfirmation()
      case PublishFailed(reason) if reason.getMessage.contains("rejecting replacement") =>
        log.info("could not publish tx: a conflicting mempool transaction is already in the mempool")
        sendResult(TxRejected(cmd.tx.txid, TxRejectedReason.ConflictingTxUnconfirmed))
      case PublishFailed(reason) if reason.getMessage.contains("bad-txns-inputs-missingorspent") =>
        // This can only happen if one of our inputs is already spent by a confirmed transaction or doesn't exist (e.g.
        // unconfirmed wallet input that has been replaced).
        checkInputStatus(cmd.input)
        Behaviors.same
      case PublishFailed(reason) =>
        log.error("could not publish transaction", reason)
        sendResult(TxRejected(cmd.tx.txid, TxRejectedReason.UnknownTxFailure))
      case status: InputStatus =>
        if (status.spentConfirmed) {
          log.info("could not publish tx: a conflicting transaction is already confirmed")
          sendResult(TxRejected(cmd.tx.txid, TxRejectedReason.ConflictingTxConfirmed))
        } else if (status.spentUnconfirmed) {
          log.info("could not publish tx: a conflicting mempool transaction is already in the mempool")
          sendResult(TxRejected(cmd.tx.txid, TxRejectedReason.ConflictingTxUnconfirmed))
        } else {
          log.info("could not publish tx: one of our wallet inputs is not available")
          sendResult(TxRejected(cmd.tx.txid, TxRejectedReason.WalletInputGone))
        }
      case CheckInputFailed(reason) =>
        log.error("could not check input status", reason)
        sendResult(TxRejected(cmd.tx.txid, TxRejectedReason.TxSkipped(retryNextBlock = true))) // we act as if the input is potentially still spendable
      case Stop =>
        Behaviors.stopped
    }
  }

  def waitForConfirmation(): Behavior[Command] = {
    val messageAdapter = context.messageAdapter[CurrentBlockCount](cbc => WrappedCurrentBlockCount(cbc.blockCount))
    context.system.eventStream ! EventStream.Subscribe(messageAdapter)
    Behaviors.receiveMessagePartial {
      case WrappedCurrentBlockCount(_) =>
        context.pipeToSelf(bitcoinClient.getTxConfirmations(cmd.tx.txid)) {
          case Success(Some(confirmations)) => TxConfirmations(confirmations)
          case Success(None) => TxNotFound
          case Failure(reason) => GetTxConfirmationsFailed(reason)
        }
        Behaviors.same
      case TxConfirmations(confirmations) =>
        if (confirmations == 1) {
          log.info("txid={} has been confirmed, waiting to reach min depth", cmd.tx.txid)
        }
        if (nodeParams.minDepthBlocks <= confirmations) {
          log.info("txid={} has reached min depth", cmd.tx.txid)
          context.system.eventStream ! EventStream.Publish(TransactionConfirmed(loggingInfo.channelId_opt.getOrElse(ByteVector32.Zeroes), loggingInfo.remoteNodeId, cmd.tx))
          sendResult(TxConfirmed(cmd.tx), Some(messageAdapter))
        } else {
          Behaviors.same
        }
      case TxNotFound =>
        log.warn("txid={} has been evicted from the mempool", cmd.tx.txid)
        checkInputStatus(cmd.input)
        Behaviors.same
      case GetTxConfirmationsFailed(reason) =>
        log.error("could not get tx confirmations", reason)
        // We will retry when the next block is found.
        Behaviors.same
      case status: InputStatus =>
        if (status.spentConfirmed) {
          log.info("tx was evicted from the mempool: a conflicting transaction has been confirmed")
          sendResult(TxRejected(cmd.tx.txid, TxRejectedReason.ConflictingTxConfirmed))
        } else if (status.spentUnconfirmed) {
          log.info("tx was evicted from the mempool: a conflicting transaction replaced it")
          sendResult(TxRejected(cmd.tx.txid, TxRejectedReason.ConflictingTxUnconfirmed))
        } else {
          log.info("tx was evicted from the mempool: one of our wallet inputs disappeared")
          sendResult(TxRejected(cmd.tx.txid, TxRejectedReason.WalletInputGone))
        }
      case CheckInputFailed(reason) =>
        log.error("could not check input status", reason)
        sendResult(TxRejected(cmd.tx.txid, TxRejectedReason.TxSkipped(retryNextBlock = true)), Some(messageAdapter))
      case Stop =>
        context.system.eventStream ! EventStream.Unsubscribe(messageAdapter)
        Behaviors.stopped
    }
  }

  def sendResult(result: TxResult, blockSubscriber_opt: Option[ActorRef[CurrentBlockCount]] = None): Behavior[Command] = {
    blockSubscriber_opt.foreach(actor => context.system.eventStream ! EventStream.Unsubscribe(actor))
    cmd.replyTo ! result
    Behaviors.stopped
  }

  private def checkInputStatus(input: OutPoint): Unit = {
    val checkInputTask = for {
      parentConfirmations <- bitcoinClient.getTxConfirmations(input.txid)
      spendableMempoolExcluded <- bitcoinClient.isTransactionOutputSpendable(input.txid, input.index.toInt, includeMempool = false)
      spendableMempoolIncluded <- bitcoinClient.isTransactionOutputSpendable(input.txid, input.index.toInt, includeMempool = true)
    } yield computeInputStatus(parentConfirmations, spendableMempoolExcluded, spendableMempoolIncluded)
    context.pipeToSelf(checkInputTask) {
      case Success(status) => status
      case Failure(reason) => CheckInputFailed(reason)
    }
  }

  private def computeInputStatus(parentConfirmations: Option[Int], spendableMempoolExcluded: Boolean, spendableMempoolIncluded: Boolean): InputStatus = {
    parentConfirmations match {
      case Some(0) => InputStatus(spentConfirmed = false, spentUnconfirmed = !spendableMempoolIncluded)
      case Some(_) => InputStatus(!spendableMempoolExcluded, spendableMempoolExcluded && !spendableMempoolIncluded)
      case None => InputStatus(spentConfirmed = false, spentUnconfirmed = false)
    }
  }

}