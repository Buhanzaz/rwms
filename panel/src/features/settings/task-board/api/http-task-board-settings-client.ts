import type {
  QueueOrderItem,
  TaskBoardSettingsClient,
} from "@/features/settings/task-board/api/task-board-settings-client"
import type {
  WorkerClassDto,
  WorkerClassRequest,
  WorkerDto,
  WorkerGroupDto,
  WorkerGroupRequest,
  WorkerRequest,
  WorkQueueDto,
  WorkQueueRequest,
} from "@/features/settings/task-board/model/task-board-settings"
import { bearerRequest } from "@/lib/api-client"
import { getGatewayRuntimeConfig } from "@/lib/gateway-config"

const TASK_BOARD_API = getGatewayRuntimeConfig().taskBoardApiBaseUrl

function warehouseEndpoint(warehouseId: string) {
  return `${TASK_BOARD_API}/warehouses/${encodeURIComponent(warehouseId)}`
}

function json(method: string, body: unknown): RequestInit {
  return { method, body: JSON.stringify(body) }
}

export class HttpTaskBoardSettingsClient implements TaskBoardSettingsClient {
  listQueues(token: string, warehouseId: string) {
    return bearerRequest<WorkQueueDto[]>(
      token,
      `${warehouseEndpoint(warehouseId)}/work-queues`
    )
  }
  createQueue(token: string, warehouseId: string, request: WorkQueueRequest) {
    return bearerRequest<WorkQueueDto>(
      token,
      `${warehouseEndpoint(warehouseId)}/work-queues`,
      json("POST", request)
    )
  }
  updateQueue(
    token: string,
    warehouseId: string,
    id: string,
    request: WorkQueueRequest
  ) {
    return bearerRequest<WorkQueueDto>(
      token,
      `${warehouseEndpoint(warehouseId)}/work-queues/${encodeURIComponent(id)}`,
      json("PUT", request)
    )
  }
  deleteQueue(
    token: string,
    warehouseId: string,
    id: string,
    expectedVersion: number
  ) {
    return bearerRequest<void>(
      token,
      `${warehouseEndpoint(warehouseId)}/work-queues/${encodeURIComponent(id)}?expectedVersion=${expectedVersion}`,
      { method: "DELETE" }
    )
  }
  reorderQueues(token: string, warehouseId: string, queues: QueueOrderItem[]) {
    return bearerRequest<WorkQueueDto[]>(
      token,
      `${warehouseEndpoint(warehouseId)}/work-queue-order`,
      json("PUT", { queues })
    )
  }

  listClasses(token: string) {
    return bearerRequest<WorkerClassDto[]>(
      token,
      `${TASK_BOARD_API}/worker-classes`
    )
  }
  createClass(token: string, request: WorkerClassRequest) {
    return bearerRequest<WorkerClassDto>(
      token,
      `${TASK_BOARD_API}/worker-classes`,
      json("POST", request)
    )
  }
  updateClass(token: string, id: string, request: WorkerClassRequest) {
    return bearerRequest<WorkerClassDto>(
      token,
      `${TASK_BOARD_API}/worker-classes/${encodeURIComponent(id)}`,
      json("PUT", request)
    )
  }
  deleteClass(token: string, id: string, expectedVersion: number) {
    return bearerRequest<void>(
      token,
      `${TASK_BOARD_API}/worker-classes/${encodeURIComponent(id)}?expectedVersion=${expectedVersion}`,
      { method: "DELETE" }
    )
  }

  listWorkers(token: string, warehouseId: string) {
    return bearerRequest<WorkerDto[]>(
      token,
      `${warehouseEndpoint(warehouseId)}/workers`
    )
  }
  createWorker(token: string, warehouseId: string, request: WorkerRequest) {
    return bearerRequest<WorkerDto>(
      token,
      `${warehouseEndpoint(warehouseId)}/workers`,
      json("POST", request)
    )
  }
  updateWorker(
    token: string,
    warehouseId: string,
    id: string,
    request: WorkerRequest
  ) {
    return bearerRequest<WorkerDto>(
      token,
      `${warehouseEndpoint(warehouseId)}/workers/${encodeURIComponent(id)}`,
      json("PUT", request)
    )
  }
  deleteWorker(
    token: string,
    warehouseId: string,
    id: string,
    expectedVersion: number
  ) {
    return bearerRequest<void>(
      token,
      `${warehouseEndpoint(warehouseId)}/workers/${encodeURIComponent(id)}?expectedVersion=${expectedVersion}`,
      { method: "DELETE" }
    )
  }
  resetWorkerCredentials(
    token: string,
    warehouseId: string,
    id: string,
    expectedVersion: number,
    password: string
  ) {
    return bearerRequest<WorkerDto>(
      token,
      `${warehouseEndpoint(warehouseId)}/workers/${encodeURIComponent(id)}/credentials/reset`,
      json("POST", { expectedVersion, password })
    )
  }
  disableWorkerCredentials(
    token: string,
    warehouseId: string,
    id: string,
    expectedVersion: number
  ) {
    return bearerRequest<WorkerDto>(
      token,
      `${warehouseEndpoint(warehouseId)}/workers/${encodeURIComponent(id)}/credentials/disable`,
      json("POST", { expectedVersion })
    )
  }

  listGroups(token: string, warehouseId: string) {
    return bearerRequest<WorkerGroupDto[]>(
      token,
      `${warehouseEndpoint(warehouseId)}/worker-groups`
    )
  }
  createGroup(token: string, warehouseId: string, request: WorkerGroupRequest) {
    return bearerRequest<WorkerGroupDto>(
      token,
      `${warehouseEndpoint(warehouseId)}/worker-groups`,
      json("POST", request)
    )
  }
  updateGroup(
    token: string,
    warehouseId: string,
    id: string,
    request: WorkerGroupRequest
  ) {
    return bearerRequest<WorkerGroupDto>(
      token,
      `${warehouseEndpoint(warehouseId)}/worker-groups/${encodeURIComponent(id)}`,
      json("PUT", request)
    )
  }
  deleteGroup(
    token: string,
    warehouseId: string,
    id: string,
    expectedVersion: number
  ) {
    return bearerRequest<void>(
      token,
      `${warehouseEndpoint(warehouseId)}/worker-groups/${encodeURIComponent(id)}?expectedVersion=${expectedVersion}`,
      { method: "DELETE" }
    )
  }
}
