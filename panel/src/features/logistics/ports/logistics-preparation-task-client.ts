export type LogisticsPreparationTaskCommand = {
  serviceWarehouseId: string
  externalTaskId: string
  cabinNumber: string
  taskText: string
}

export type LogisticsPreparationTaskResult = {
  boardTaskId: string
  boardTaskVersion: number
  queueId: string
  queueCode: string
}

export type LogisticsPreparationTaskCancelCommand = {
  serviceWarehouseId: string
  externalTaskId: string
  expectedTaskVersion: number
  reason: string
}

export interface LogisticsPreparationTaskClient {
  dispatch(
    accessToken: string,
    command: LogisticsPreparationTaskCommand
  ): Promise<LogisticsPreparationTaskResult>
  cancel(
    accessToken: string,
    command: LogisticsPreparationTaskCancelCommand
  ): Promise<{ boardTaskVersion: number }>
}
