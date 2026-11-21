import { health } from '@/service/generated/health'
import type { HealthStatus } from '@/types/api'

export const getHealth = (): Promise<HealthStatus> => health()
