export type WarehouseInfo = {
  id: string
  code: string
  name: string
  city: string
  active: boolean
}

const mockWarehouses: WarehouseInfo[] = [
  {
    id: "spb",
    code: "СПБ",
    name: "Склад СПБ",
    city: "Санкт-Петербург",
    active: true,
  },
  {
    id: "msk",
    code: "МСК",
    name: "Склад МСК",
    city: "Москва",
    active: true,
  },
]

function delay<T>(data: T, timeout = 300): Promise<T> {
  return new Promise((resolve) => {
    window.setTimeout(() => resolve(data), timeout)
  })
}

export async function getWarehouses(): Promise<WarehouseInfo[]> {
  return delay(mockWarehouses)
}
