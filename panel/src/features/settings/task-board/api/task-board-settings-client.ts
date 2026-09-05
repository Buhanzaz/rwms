import type {
  DriverQueueRequest,
  QueueDefinitionDto,
  QueueDefinitionRequest,
  QueueLinkRequest,
  WorkerClassDto,
  WorkerClassRequest,
  WorkerDto,
  WorkerGroupDto,
  WorkerGroupRequest,
  WorkerRequest,
  WorkQueueDto,
} from "@/features/settings/task-board/model/task-board-settings"

export type QueueDefinitionOrderItem = {
  definitionId: string
  expectedVersion: number
}

export interface TaskBoardSettingsClient {
  listQueueDefinitions(token: string): Promise<QueueDefinitionDto[]>
  createQueueDefinition(
    token: string,
    request: QueueDefinitionRequest
  ): Promise<QueueDefinitionDto>
  updateQueueDefinition(
    token: string,
    id: string,
    request: QueueDefinitionRequest
  ): Promise<QueueDefinitionDto>
  deleteQueueDefinition(
    token: string,
    id: string,
    expectedVersion: number
  ): Promise<void>
  linkQueueDefinitions(
    token: string,
    id: string,
    request: QueueLinkRequest
  ): Promise<QueueDefinitionDto[]>
  reorderQueueDefinitions(
    token: string,
    definitions: QueueDefinitionOrderItem[]
  ): Promise<QueueDefinitionDto[]>

  listQueues(token: string, warehouseId: string): Promise<WorkQueueDto[]>
  updateDriverQueue(
    token: string,
    warehouseId: string,
    request: DriverQueueRequest
  ): Promise<WorkQueueDto>

  listClasses(token: string): Promise<WorkerClassDto[]>
  createClass(
    token: string,
    request: WorkerClassRequest
  ): Promise<WorkerClassDto>
  updateClass(
    token: string,
    id: string,
    request: WorkerClassRequest
  ): Promise<WorkerClassDto>
  deleteClass(token: string, id: string, expectedVersion: number): Promise<void>

  listWorkers(token: string, warehouseId: string): Promise<WorkerDto[]>
  createWorker(
    token: string,
    warehouseId: string,
    request: WorkerRequest
  ): Promise<WorkerDto>
  updateWorker(
    token: string,
    warehouseId: string,
    id: string,
    request: WorkerRequest
  ): Promise<WorkerDto>
  deleteWorker(
    token: string,
    warehouseId: string,
    id: string,
    expectedVersion: number
  ): Promise<void>
  resetWorkerCredentials(
    token: string,
    warehouseId: string,
    id: string,
    expectedVersion: number,
    password: string
  ): Promise<WorkerDto>
  disableWorkerCredentials(
    token: string,
    warehouseId: string,
    id: string,
    expectedVersion: number
  ): Promise<WorkerDto>
  enableWorkerCredentials(
    token: string,
    warehouseId: string,
    id: string,
    expectedVersion: number
  ): Promise<WorkerDto>
  listGroups(token: string, warehouseId: string): Promise<WorkerGroupDto[]>
  createGroup(
    token: string,
    warehouseId: string,
    request: WorkerGroupRequest
  ): Promise<WorkerGroupDto>
  updateGroup(
    token: string,
    warehouseId: string,
    id: string,
    request: WorkerGroupRequest
  ): Promise<WorkerGroupDto>
  deleteGroup(
    token: string,
    warehouseId: string,
    id: string,
    expectedVersion: number
  ): Promise<void>
  disableGroup(
    token: string,
    warehouseId: string,
    id: string,
    expectedVersion: number,
    reason: string
  ): Promise<WorkerGroupDto>
  enableGroup(
    token: string,
    warehouseId: string,
    id: string,
    expectedVersion: number,
    reason: string | null
  ): Promise<WorkerGroupDto>
}
