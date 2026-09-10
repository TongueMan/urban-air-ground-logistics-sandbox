<template>
  <LogisticsMissionMap
    :devices="mission.source.devices"
    :mission="mission.source.mission"
    :selected-id="context.focusedActorId.value"
    :selected-airspace-id="runtime.selectedAirspaceId.value"
    :time-cursor="context.timeCursor.value"
    :time-mode="context.timeMode.value"
    :planning-preview="runtime.plannerOpen.value && Boolean(runtime.taskPreview.value)"
    :tutorial-red-conflict-locked="runtime.tutorialRedConflictLocked.value"
    :model-assignments="modelAssignments"
    @select="context.focusActor"
    @select-airspace="runtime.selectAirspace"
    @follow-change="runtime.setMapFollowingDevice"
  />
</template>

<script setup>
import { computed, defineAsyncComponent } from 'vue'
import { useMissionContext } from '../../mission/context/useMissionContext'

const LogisticsMissionMap = defineAsyncComponent(() => import('../LogisticsMissionMap.vue'))

const props = defineProps({
  runtime: { type: Object, required: true },
  fleetRuntime: { type: Object, required: true }
})
const context = useMissionContext()
const mission = computed(() => context.mission.value)
const runtime = props.runtime
const fleetRuntime = props.fleetRuntime
const modelAssignments = computed(() => {
  const live = fleetRuntime.activeDeployments.value
  const ground = mission.value.groundVehicle
  const air = mission.value.airVehicle
  if (!ground?.modelAssetId && !air?.modelAssetId) return live
  const assignments = { ...live }
  if (ground?.modelAssetId) assignments.ground_vehicle = {
    role: 'ground_vehicle', category: 'GROUND', assetId: ground.assetId,
    typeId: ground.typeId, modelAssetId: ground.modelAssetId, name: ground.name,
    independentRoute: false
  }
  if (air?.modelAssetId) assignments.smart_drone = {
    role: 'smart_drone', category: 'AIR', assetId: air.assetId,
    typeId: air.typeId, modelAssetId: air.modelAssetId, name: air.name,
    independentRoute: Boolean(air.independentRoute)
  }
  return assignments
})
</script>
