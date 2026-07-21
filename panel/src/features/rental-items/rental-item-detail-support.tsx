import { Badge } from "@/components/ui/badge"
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from "@/components/ui/card"
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table"

const SANITARY_CHARACTERISTIC_PATTERN = /(?:душ|туалет|раковин|бойлер|санузел)/i
const COMPOUND_METAL_DOOR_CHARACTERISTIC = "Металлическая дверь, кондиционер"
const COMPOUND_CHARACTERISTIC_TOKEN = "__METAL_DOOR_AND_AC__"

function splitCharacteristics(value: string | null) {
  if (!value?.trim()) return []

  return value
    .replaceAll(
      COMPOUND_METAL_DOOR_CHARACTERISTIC,
      COMPOUND_CHARACTERISTIC_TOKEN
    )
    .split(/[,;\n]+/)
    .map((item) =>
      item
        .trim()
        .replaceAll(
          COMPOUND_CHARACTERISTIC_TOKEN,
          COMPOUND_METAL_DOOR_CHARACTERISTIC
        )
    )
    .filter(Boolean)
}

export function CharacteristicTags({ value }: { value: string | null }) {
  const values = splitCharacteristics(value)
  if (values.length === 0) {
    return <span className="text-muted-foreground">—</span>
  }

  return (
    <div className="flex flex-wrap gap-1.5">
      {values.map((item) => (
        <Badge
          key={item}
          variant={
            SANITARY_CHARACTERISTIC_PATTERN.test(item) ? "default" : "secondary"
          }
        >
          {item}
        </Badge>
      ))}
    </div>
  )
}

export function EmptyDossierRegister({
  title,
  description,
  columns,
}: {
  title: string
  description: string
  columns: string[]
}) {
  return (
    <Card>
      <CardHeader>
        <CardTitle>{title}</CardTitle>
        <CardDescription>{description}</CardDescription>
      </CardHeader>
      <CardContent>
        <Table>
          <TableHeader>
            <TableRow>
              {columns.map((column) => (
                <TableHead key={column}>{column}</TableHead>
              ))}
            </TableRow>
          </TableHeader>
          <TableBody>
            <TableRow>
              <TableCell
                colSpan={columns.length}
                className="h-24 text-center text-muted-foreground"
              >
                Записей нет
              </TableCell>
            </TableRow>
          </TableBody>
        </Table>
      </CardContent>
    </Card>
  )
}
