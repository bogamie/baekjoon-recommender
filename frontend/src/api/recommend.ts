import client from './client'

export interface ProblemInfo {
  id: number
  title: string
  level: number
  levelName: string
  tags: string[]
  solvedCount: number
  acceptanceRate: number
}

export interface ReasonInfo {
  category: string
  categoryName: string
  proficiency: string
  message: string
}

export interface RecommendationSlot {
  slotType: 'REVIEW' | 'GROWTH' | 'CHALLENGE' | 'ENTRY' | 'EXPLORE'
  problem: ProblemInfo
  reason: ReasonInfo
}

export interface UserProfileSummary {
  tier: string
  totalSolved: number
  advancedCount: number
  intermediateCount: number
  beginnerCount: number
}

export interface RecommendationResponse {
  recommendations: RecommendationSlot[]
  userProfile: UserProfileSummary
}

export function getRecommendations() {
  return client.get<RecommendationResponse>('/api/recommend')
}

export function skipProblem(problemId: number) {
  return client.post('/api/recommend/skip', { problemId })
}

export function refreshRecommendations() {
  return client.post<RecommendationResponse>('/api/recommend/refresh')
}
