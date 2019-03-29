package net.corda.examples.obligation.workflow.flows

import co.paralleluniverse.fibers.Suspendable
import net.corda.confidential.SwapIdentitiesFlow
import net.corda.core.contracts.Amount
import net.corda.core.flows.*
import net.corda.core.identity.Party
import net.corda.core.transactions.SignedTransaction
import net.corda.core.transactions.TransactionBuilder
import net.corda.core.utilities.ProgressTracker
import net.corda.core.utilities.ProgressTracker.Step
import net.corda.core.utilities.seconds
import net.corda.core.utilities.unwrap
import net.corda.examples.obligation.contract.Obligation
import net.corda.examples.obligation.contract.ObligationContract
import net.corda.examples.obligation.contract.ObligationContract.Companion.OBLIGATION_CONTRACT_ID
import java.util.*

object IssueObligation {
    @InitiatingFlow(version = 2)
    @StartableByRPC
    class Initiator(private val amount: Amount<Currency>,
                    private val lender: Party,
                    private val anonymous: Boolean = true) : ObligationBaseFlow() {

        companion object {
            object INITIALISING : Step("Performing initial steps.")
            object BUILDING : Step("Building and verifying transaction.")
            object SIGNING : Step("Signing transaction.")
            object COLLECTING : Step("Collecting counterparty signature.") {
                override fun childProgressTracker() = CollectSignaturesFlow.tracker()
            }

            object FINALISING : Step("Finalising transaction.") {
                override fun childProgressTracker() = FinalityFlow.tracker()
            }

            fun tracker() = ProgressTracker(INITIALISING, BUILDING, SIGNING, COLLECTING, FINALISING)
        }

        override val progressTracker: ProgressTracker = tracker()

        @Suspendable
        override fun call(): SignedTransaction {
            // Step 1. Initialisation.
            progressTracker.currentStep = INITIALISING
            val lenderFlowSession = initiateFlow(lender)
            lenderFlowSession.send(anonymous)
            val config = serviceHub.getAppContext().config
            val obligation = createObligation(lenderFlowSession, anonymous, config.getBoolean("setDefaultField"))

            val ourSigningKey = obligation.borrower.owningKey

            // Step 2. Building.
            progressTracker.currentStep = BUILDING
            val utx = TransactionBuilder(firstNotary)
                    .addOutputState(obligation, OBLIGATION_CONTRACT_ID)
                    .addCommand(ObligationContract.Commands.Issue(), obligation.participants.map { it.owningKey })
                    .setTimeWindow(serviceHub.clock.instant(), 30.seconds)

            // Step 3. Sign the transaction.
            progressTracker.currentStep = SIGNING
            val ptx = serviceHub.signInitialTransaction(utx, ourSigningKey)

            // Step 4. Get the counter-party signature.
            progressTracker.currentStep = COLLECTING
            val stx = subFlow(CollectSignaturesFlow(
                    ptx,
                    setOf(lenderFlowSession),
                    listOf(ourSigningKey),
                    Companion.COLLECTING.childProgressTracker())
            )

            // Step 5. Finalise the transaction.
            progressTracker.currentStep = FINALISING
            return if (lenderFlowSession.getCounterpartyFlowInfo().flowVersion >= 2) {
                subFlow(FinalityFlow(stx, setOf(lenderFlowSession), FINALISING.childProgressTracker()))
            } else {
                subFlow(FinalityFlow(stx, FINALISING.childProgressTracker()))
            }
        }

        @Suspendable
        private fun createAnonymousObligation(lenderSession: FlowSession): Obligation {
            val anonymousIdentitiesResult = subFlow(SwapIdentitiesFlow(lenderSession))

            return Obligation(amount, anonymousIdentitiesResult[lenderSession.counterparty]!!, anonymousIdentitiesResult[ourIdentity]!!)
        }

        @Suspendable
        private fun createObligation(lenderSession: FlowSession, anonymous: Boolean, setDefaulted: Boolean): Obligation {
            val (lenderId, borrowerId) = if (anonymous) {
                val anonymousIdentitiesResult = subFlow(SwapIdentitiesFlow(lenderSession))
                Pair(anonymousIdentitiesResult[lenderSession.counterparty]!!, anonymousIdentitiesResult[ourIdentity]!!)
            } else {
                Pair(lender, ourIdentity)
            }

            val defaulted = if (setDefaulted) false else null

            return Obligation(amount, lenderId, borrowerId, defaulted = defaulted)
        }
    }

    @InitiatedBy(IssueObligation.Initiator::class)
    class Responder(private val otherFlow: FlowSession) : FlowLogic<SignedTransaction>() {
        @Suspendable
        override fun call(): SignedTransaction {
            val anonymous = otherFlow.receive<Boolean>().unwrap { it }
            if (anonymous) {
                subFlow(SwapIdentitiesFlow(otherFlow))
            }
            val stx = subFlow(SignTxFlowNoChecking(otherFlow))
            return if (otherFlow.getCounterpartyFlowInfo().flowVersion >= 2) {
                subFlow(ReceiveFinalityFlow(otherFlow, stx.id))
            } else {
                waitForLedgerCommit(stx.id)
            }
        }
    }
}