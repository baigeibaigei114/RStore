import { onBeforeUnmount, ref } from 'vue'
import { createRefreshController } from '@/utils/taskPresentation'

export function useTaskRefresh(work: () => Promise<boolean>) {
  const polling = ref(false)
  const controller = createRefreshController(work, setTimeout, clearTimeout, () => { polling.value = false })
  function stop() { controller.stop(); polling.value = false }
  function refresh() {
    stop()
    if (!document.hidden) { polling.value = true; controller.start() }
  }
  function visibility() { if (document.hidden) stop(); else refresh() }
  document.addEventListener('visibilitychange', visibility)
  onBeforeUnmount(() => { stop(); document.removeEventListener('visibilitychange', visibility) })
  return { polling, refresh, stop }
}
