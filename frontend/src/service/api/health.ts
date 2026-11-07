import request from '@/request'
import type { HealthStatus } from '@/types/api'

export const getHealth = (): Promise<HealthStatus> => request<HealthStatus>('/api/health')
