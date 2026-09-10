<template><div class="spatial-overlay-host"><slot /></div></template>

<style scoped>
.spatial-overlay-host { position:absolute; inset:0; z-index:var(--layer-instrument); overflow:hidden; pointer-events:none; }
</style>
