<template>
  <div v-if="!desktopReady" class="mobile-gate">
    <div class="gate-mark">运</div>
    <h1>城市空地协同物流运营沙盘</h1>
    <p>物流运营界面当前支持宽度不小于 1100px 的桌面设备。</p>
  </div>

  <div v-else class="spatial-app">
    <MissionContextProvider :context="runtime.context">
      <SpatialMissionScreen :runtime="runtime" :fleet-runtime="fleetRuntime" />
    </MissionContextProvider>
  </div>
</template>

<script setup>
import { onBeforeUnmount, onMounted, ref } from 'vue'
import MissionContextProvider from './mission/context/MissionContextProvider.vue'
import { useLogisticsMissionRuntime } from './mission/runtime/useLogisticsMissionRuntime'
import { useFleetRuntime } from './fleet/useFleetRuntime'
import { initializeApplication } from './runtime/initializeApplication.mjs'
import SpatialMissionScreen from './screens/SpatialMissionScreen.vue'

const fleetRuntime = useFleetRuntime()
const runtime = useLogisticsMissionRuntime({ onEconomy: economy => fleetRuntime.mergeMissionEconomy(economy) })
const desktopReady = ref(false)
let desktopQuery = null
let desktopListener = null
let initializationPromise = null

function initializeDesktop() {
  if (!initializationPromise) {
    initializationPromise = initializeApplication(runtime, fleetRuntime)
      .finally(() => { initializationPromise = null })
  }
  return initializationPromise
}

onMounted(async () => {
  desktopQuery = window.matchMedia('(min-width: 1100px)')
  desktopListener = event => {
    desktopReady.value = event.matches
    if (event.matches) initializeDesktop()
  }
  desktopReady.value = desktopQuery.matches
  desktopQuery.addEventListener('change', desktopListener)
  if (desktopReady.value) await initializeDesktop()
})

onBeforeUnmount(() => {
  if (desktopListener) desktopQuery?.removeEventListener('change', desktopListener)
})
</script>
