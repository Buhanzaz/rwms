import { LoginForm } from "@/login-form";

export default function App() {
  return (
    <main className="auth-page">
      <video
        className="auth-page__background"
        aria-hidden="true"
        tabIndex={-1}
        autoPlay
        loop
        muted
        playsInline
        preload="metadata"
      >
        <source src="assets/background.webm" type="video/webm" />
      </video>
      <div className="auth-page__wash" aria-hidden="true" />
      <div className="auth-page__content">
        <LoginForm />
      </div>
    </main>
  );
}
