export const metadata = {
  title: "RWMS Worker — Android",
  description: "Скачивание Android-приложения RWMS для работников.",
};

export default function RootLayout({ children }) {
  return (
    <html lang="ru">
      <body>{children}</body>
    </html>
  );
}
