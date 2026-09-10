<template><slot /></template>

<script setup>
import { provideMissionContext } from './useMissionContext'

const props = defineProps({ context: { type: Object, required: true } })
provideMissionContext(props.context)
</script>
