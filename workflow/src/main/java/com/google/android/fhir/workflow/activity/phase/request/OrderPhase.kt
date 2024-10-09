/*
 * Copyright 2024 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.android.fhir.workflow.activity.phase.request

import com.google.android.fhir.workflow.activity.phase.Phase
import com.google.android.fhir.workflow.activity.phase.checkEquals
import com.google.android.fhir.workflow.activity.phase.idType
import com.google.android.fhir.workflow.activity.resource.request.CPGRequestResource
import com.google.android.fhir.workflow.activity.resource.request.Intent
import com.google.android.fhir.workflow.activity.resource.request.Status
import java.util.UUID
import org.opencds.cqf.fhir.api.Repository

/**
 * Provides implementation of the order phase of the activity flow. See
 * [general-activity-flow](https://build.fhir.org/ig/HL7/cqf-recommendations/activityflow.html#general-activity-flow)
 * for more info.
 */
@Suppress(
  "UnstableApiUsage", /* Repository is marked @Beta */
  "UNCHECKED_CAST", /* Cast type erased CPGRequestResource<*> & CPGEventResource<*> to a concrete type classes */
)
class OrderPhase<R : CPGRequestResource<*>>(
  /** Implementation of [Repository] to store / retrieve FHIR resources. */
  repository: Repository,
  /**
   * Concrete implementation of sealed [CPGRequestResource] class. e.g. `CPGCommunicationRequest`.
   */
  r: R,
) : BaseRequestPhase<R>(repository, r, Phase.PhaseName.ORDER) {

  companion object {

    private val AllowedIntents = listOf(Intent.PROPOSAL, Intent.PLAN)
    private val AllowedPhases = listOf(Phase.PhaseName.PROPOSAL, Phase.PhaseName.PLAN)

    /**
     * Creates a draft order of type [R] based on the state of the provided [inputPhase]. See
     * [beginOrder](https://build.fhir.org/ig/HL7/cqf-recommendations/activityflow.html#order) for
     * more details.
     */
    internal fun <R : CPGRequestResource<*>> prepare(inputPhase: Phase): Result<R> = runCatching {
      check(inputPhase.getPhaseName() in AllowedPhases) {
        "An Order can't be created for a flow in ${inputPhase.getPhaseName().name} phase. "
      }

      val inputRequest = (inputPhase as BaseRequestPhase<*>).request

      check(inputRequest.getIntent() in AllowedIntents) {
        "Order can't be created for a request with ${inputRequest.getIntent()} intent."
      }

      check(inputRequest.getStatus() == Status.ACTIVE) {
        "${inputPhase.getPhaseName().name} request is still in ${inputRequest.getStatusCode()} status."
      }

      inputRequest.copy(
        UUID.randomUUID().toString(),
        Status.DRAFT,
        Intent.ORDER,
      ) as R
    }

    /**
     * Creates a [OrderPhase] of request type [R] based on the [inputPhase] and [inputOrder]. See
     * [endPlan](https://build.fhir.org/ig/HL7/cqf-recommendations/activityflow.html#plan) for more
     * details.
     */
    fun <R : CPGRequestResource<*>> initiate(
      repository: Repository,
      inputPhase: Phase,
      inputOrder: R,
    ): Result<OrderPhase<R>> = runCatching {
      check(inputPhase.getPhaseName() in AllowedPhases) {
        "An Order can't be started for a flow in ${inputPhase.getPhaseName().name} phase."
      }

      val currentPhase = inputPhase as BaseRequestPhase<*>

      val basedOn = inputOrder.getBasedOn()
      require(basedOn != null) { "${inputOrder.resource.resourceType}.basedOn can't be null." }

      require(checkEquals(basedOn, currentPhase.request.asReference())) {
        "Provided draft is not based on the request in current phase."
      }

      val basedOnRequest =
        repository.read(inputOrder.resource.javaClass, basedOn.idType)?.let {
          CPGRequestResource.of(inputOrder, it)
        }

      require(basedOnRequest != null) { "Couldn't find ${basedOn.reference} in the database." }

      require(basedOnRequest.getIntent() in AllowedIntents) {
        "Order can't be based on a request with ${basedOnRequest.getIntent()} intent."
      }

      require(basedOnRequest.getStatus() == Status.ACTIVE) {
        "Plan can't be based on a request with ${basedOnRequest.getStatusCode()} status."
      }

      require(inputOrder.getIntent() == Intent.ORDER) {
        "Input request has '${inputOrder.getIntent()}' intent."
      }

      require(inputOrder.getStatus() in AllowedStatusForPhaseStart) {
        "Input request is in ${inputOrder.getStatusCode()} status."
      }

      basedOnRequest.setStatus(Status.COMPLETED)

      repository.create(inputOrder.resource)
      repository.update(basedOnRequest.resource)
      OrderPhase(repository, inputOrder)
    }
  }
}
