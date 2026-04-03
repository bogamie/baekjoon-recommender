import client from './client'

export interface Summary {
  solvedacTier: number | null
  rating: number | null
  totalSolved: number
  globalStatus: string
  solvedacHandle: string | null
}

export interface TagStat {
  tagKey: string
  solvedCount: number
  avgLevel: number
  maxLevel: number
  lastSolvedAt: string
  proficiency: string
  isDormant: boolean
}

export function getSummary() {
  return client.get<Summary>('/api/dashboard/summary')
}

export function getTagStats() {
  return client.get<TagStat[]>('/api/dashboard/tags')
}
