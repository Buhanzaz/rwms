import { Badge } from "@/components/ui/badge"
import {
  Card,
  CardDescription,
  CardHeader,
  CardTitle,
} from "@/components/ui/card"

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

export function UnavailableDossierSection({
  title,
  description,
}: {
  title: string
  description: string
}) {
  return (
    <Card>
      <CardHeader>
        <CardTitle>{title}</CardTitle>
        <CardDescription>{description}</CardDescription>
      </CardHeader>
    </Card>
  )
}
