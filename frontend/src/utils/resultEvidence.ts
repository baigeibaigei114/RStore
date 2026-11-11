import type { ResultSection } from '@/types/ai'

export const sectionLabels: Record<ResultSection, string> = { NW: '左上', NE: '右上', SW: '左下', SE: '右下' }

export function readResultEvidence(version: unknown, section: unknown) {
  if (typeof version !== 'string' || !/^[a-f0-9]{64}$/.test(version) ||
    typeof section !== 'string' || !Object.hasOwn(sectionLabels, section)) return null
  return { version, section: section as ResultSection }
}
