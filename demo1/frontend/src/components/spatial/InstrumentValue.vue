<template>
  <div class="instrument-reading">
    <span class="instrument-label">{{ label }}</span>
    <strong class="instrument-value">{{ value }}<small v-if="unit">{{ unit }}</small></strong>
  </div>
</template>

<script setup>
defineProps({
  label: { type: String, required: true },
  value: { type: [String, Number], default: '—' },
  unit: { type: String, default: '' }
})
</script>

<style scoped>
.instrument-reading { min-width:0; }
.instrument-label { display:block; }
.instrument-value { display:block; margin-top:5px; font-size:clamp(1.3rem,2vw,2rem); font-weight:500; }
.instrument-value small { margin-left:3px; color:var(--signal-primary); font-size:var(--type-telemetry); font-weight:600; }
</style>
