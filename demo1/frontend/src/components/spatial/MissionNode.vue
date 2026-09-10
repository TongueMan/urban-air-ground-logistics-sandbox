<template>
  <li class="mission-node" :class="`is-${phase.state.toLowerCase()}`">
    <span class="node-index">{{ String(index + 1).padStart(2, '0') }}</span>
    <span class="node-rail"><i></i></span>
    <button type="button" :aria-current="phase.state === 'ACTIVE' ? 'step' : undefined" @click="$emit('select', phase.id)">
      <b>{{ phase.shortLabel }}</b>
      <small>{{ phase.label }}</small>
    </button>
  </li>
</template>

<script setup>
defineProps({ phase: { type: Object, required: true }, index: { type: Number, required: true } })
defineEmits(['select'])
</script>

<style scoped>
.mission-node { position:relative; display:grid; grid-template-columns:24px 12px 1fr; gap:7px; align-items:center; min-height:42px; color:var(--text-tertiary); }
.node-index { font-size:var(--type-micro); font-variant-numeric:tabular-nums; }
.node-rail { position:relative; align-self:stretch; }
.node-rail::before { content:""; position:absolute; left:5px; top:0; bottom:0; width:1px; background:var(--surface-line); }
.node-rail i { position:absolute; left:2px; top:50%; width:7px; height:7px; border:1px solid currentColor; border-radius:50%; background:var(--world-deep); transform:translateY(-50%); }
button { padding:5px 0; border:0; color:inherit; background:transparent; text-align:left; cursor:pointer; }
b,small { display:block; }
b { font-size:var(--type-telemetry); letter-spacing:.12em; }
small { margin-top:2px; color:var(--text-tertiary); font-size:var(--type-micro); }
.is-complete { color:var(--signal-mint); }
.is-active { color:var(--signal-primary); }
.is-active .node-rail i { background:currentColor; box-shadow:0 0 12px currentColor; }
.is-active b { color:var(--text-primary); font-size:.8rem; }
button:focus-visible { outline:1px solid var(--signal-primary); outline-offset:3px; }
</style>
