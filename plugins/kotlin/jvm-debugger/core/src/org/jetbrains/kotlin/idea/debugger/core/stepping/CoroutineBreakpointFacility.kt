// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.

package org.jetbrains.kotlin.idea.debugger.core.stepping

import com.intellij.debugger.DebuggerManagerEx
import com.intellij.debugger.engine.*
import com.intellij.debugger.engine.events.SuspendContextCommandImpl
import com.intellij.debugger.engine.requests.CustomProcessingLocatableEventRequestor
import com.intellij.debugger.settings.DebuggerSettings
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.util.registry.Registry
import com.sun.jdi.Location
import com.sun.jdi.event.LocatableEvent
import com.sun.jdi.request.EventRequest
import org.jetbrains.kotlin.idea.debugger.base.util.safeMethod
import org.jetbrains.kotlin.idea.debugger.core.StackFrameInterceptor
import org.jetbrains.kotlin.idea.debugger.core.getLocationOfNextInstructionAfterResume
import java.util.function.Function

object CoroutineBreakpointFacility {
    fun installResumeBreakpointInCurrentMethod(suspendContext: SuspendContextImpl): Boolean {
        val currentLocation = suspendContext.location ?: return false
        val methodLineLocations = currentLocation.method().allLineLocations()
        // In case of stepping over the last closing bracket -> step out.
        // For a suspend block, the location of the closing bracket is previous to the last
        // (the last location is the resume location and corresponds to the first line of the function).
        val resumeLocation = if (methodLineLocations.size > 2 && methodLineLocations[methodLineLocations.size - 2] == currentLocation) {
            StackFrameInterceptor.instance?.callerLocation(suspendContext)
        } else {
            currentLocation
        } ?: return false
        val nextLocationAfterResumeIndex = getLocationOfNextInstructionAfterResume(resumeLocation)
        return installCoroutineResumedBreakpoint(suspendContext, resumeLocation, nextLocationAfterResumeIndex)
    }

    fun installResumeBreakpointInCallerMethod(suspendContext: SuspendContextImpl): Boolean {
        val resumeLocation = StackFrameInterceptor.instance?.callerLocation(suspendContext) ?: return false
        val nextLocationAfterResumeIndex: Int = getLocationOfNextInstructionAfterResume(resumeLocation)
        return installCoroutineResumedBreakpoint(suspendContext, resumeLocation, nextLocationAfterResumeIndex)
    }

    private fun installCoroutineResumedBreakpoint(context: SuspendContextImpl, resumedLocation: Location, nextCallAfterResume: Int = -1): Boolean {
        val debugProcess = context.debugProcess
        debugProcess.cancelRunToCursorBreakpoint()
        val project = debugProcess.project
        val suspendAll = context.suspendPolicy == EventRequest.SUSPEND_ALL

        val useCoroutineIdFiltering = Registry.`is`("debugger.filter.breakpoints.by.coroutine.id")
        val method = resumedLocation.safeMethod() ?: return false

        val breakpoint = object : StepIntoMethodBreakpoint(method.declaringType().name(), method.name(), method.signature(), project),
                                  CustomProcessingLocatableEventRequestor {
            override fun processLocatableEvent(action: SuspendContextCommandImpl, event: LocatableEvent): Boolean {
                val result = super.processLocatableEvent(action, event)
                if (result) {
                    debugProcess.requestsManager.deleteRequest(this) // breakpoint is hit - disable the request already
                }

                if (useCoroutineIdFiltering && suspendAll) {
                    // schedule stepping over switcher after suspend-all replacement happened
                    return result
                }

                // support same thread old-way stepping
                if (!result) return false

                val suspendContextImpl = action.suspendContext ?: return true
                return scheduleStepOverCommandForSuspendSwitch(suspendContextImpl, nextCallAfterResume)
            }

            override fun customVoteSuspend(suspendContext: SuspendContextImpl): Boolean {
                if (!suspendAll) return false
                return SuspendOtherThreadsRequestor.initiateTransferToSuspendAll(suspendContext) {
                    scheduleStepOverCommandForSuspendSwitch(it, nextCallAfterResume)
                }
            }

            override fun applyAfterContextSwitch() = Function<SuspendContextImpl, Boolean> { c ->
                scheduleStepOverCommandForSuspendSwitch(c, nextCallAfterResume)
            }

            private fun scheduleStepOverCommandForSuspendSwitch(it: SuspendContextImpl, nextCallAfterResume: Int): Boolean {
                DebuggerSteppingHelper.createStepOverCommandForSuspendSwitch(it, nextCallAfterResume).prepareSteppingRequestsAndHints(it)
                // false return value will resume the execution in the `DebugProcessEvents` and
                // the scheduled above steps will perform stepping through the coroutine switch until line location.
                return false
            }
        }

        breakpoint.suspendPolicy = when (context.suspendPolicy) {
            EventRequest.SUSPEND_ALL ->
                if (useCoroutineIdFiltering && !DebuggerUtils.isAlwaysSuspendThreadBeforeSwitch()) DebuggerSettings.SUSPEND_THREAD
                else DebuggerSettings.SUSPEND_ALL
            EventRequest.SUSPEND_EVENT_THREAD -> DebuggerSettings.SUSPEND_THREAD
            EventRequest.SUSPEND_NONE -> DebuggerSettings.SUSPEND_NONE
            else -> DebuggerSettings.SUSPEND_ALL
        }
        if (!useCoroutineIdFiltering) {
            applyEmptyThreadFilter(debugProcess)
        }
        breakpoint.createRequest(debugProcess)
        debugProcess.setSteppingBreakpoint(breakpoint)

        val filterThread = debugProcess.requestsManager.filterThread
        thisLogger().debug("Resume breakpoint for $method in thread $filterThread")

        return true
    }
}

fun SuspendContextImpl.getLocationCompat(): Location? {
    return this.location
}

private fun applyEmptyThreadFilter(debugProcess: DebugProcessImpl) {
    // TODO this is nasty. Find a way to apply an empty thread filter only to the newly created breakpoint
    // TODO consider moving this filtering to event loop?
    val breakpointManager = DebuggerManagerEx.getInstanceEx(debugProcess.project).breakpointManager
    breakpointManager.removeThreadFilter(debugProcess)
}
