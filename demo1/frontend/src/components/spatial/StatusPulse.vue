<template>
  <span class="status-pulse" :class="[`status-${status.toLowerCase()}`, { 'state-pulse': animated }]" aria-hidden="true"></span>
</template>

<script setup>
defineProps({
  status: { type: String, default: 'NEUTRAL' },
  animated: { type: Boolean, default: false }
})
</script>

<style scoped>
.status-pulse { display:inline-block; width:7px; height:7px; border-radius:50%; background:var(--signal-neutral); box-shadow:0 0 0 4px rgba(119,148,163,.12); }
.status-active,.status-running { background:var(--signal-mint); box-shadow:0 0 0 4px rgba(126,240,196,.12); }
.status-warning { background:var(--signal-warning); box-shadow:0 0 0 4px rgba(255,197,111,.12); }
.status-critical,.status-failed { background:var(--signal-critical); box-shadow:0 0 0 4px rgba(255,111,120,.14); }
.status-completed { background:var(--signal-primary); box-shadow:0 0 0 4px rgba(124,231,238,.12); }
</style>
