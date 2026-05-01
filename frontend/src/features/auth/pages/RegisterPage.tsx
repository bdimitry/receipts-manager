import { zodResolver } from "@hookform/resolvers/zod";
import { useMutation } from "@tanstack/react-query";
import { useForm } from "react-hook-form";
import { Link, Navigate, useNavigate } from "react-router-dom";
import { z } from "zod";
import { registerUser } from "../api";
import { GoogleSignInButton } from "../GoogleSignInButton";
import { useAuth } from "../../../shared/auth/AuthContext";
import { useI18n } from "../../../shared/i18n/I18nContext";
import { Button } from "../../../shared/ui/Button";

const schema = z
  .object({
    email: z.string().email(),
    password: z.string().min(8),
    confirmPassword: z.string().min(8),
  })
  .refine((values) => values.password === values.confirmPassword, {
    message: "Passwords must match",
    path: ["confirmPassword"],
  });

type RegisterFormValues = z.infer<typeof schema>;

export function RegisterPage() {
  const { t } = useI18n();
  const navigate = useNavigate();
  const { isAuthenticated } = useAuth();
  const {
    register,
    handleSubmit,
    formState: { errors },
  } = useForm<RegisterFormValues>({
    resolver: zodResolver(schema),
    defaultValues: {
      email: "",
      password: "",
      confirmPassword: "",
    },
  });

  const mutation = useMutation({
    mutationFn: (values: RegisterFormValues) =>
      registerUser({
        email: values.email,
        password: values.password,
      }),
    onSuccess: () => navigate("/login", { replace: true }),
  });

  if (isAuthenticated) {
    return <Navigate to="/" replace />;
  }

  return (
    <div className="auth-shell">
      <section className="auth-panel auth-panel--hero">
        <p className="auth-panel__eyebrow">{t("register")}</p>
        <h1>{t("createAccount")}</h1>
        <p>{t("authSubtitle")}</p>
        <div className="auth-preview auth-preview--soft">
          <div className="auth-preview__copy">
            <strong>{t("quickActions")}</strong>
            <span>{t("dashboardSubtitle")}</span>
          </div>
        </div>
      </section>
      <section className="auth-panel">
        <h2>{t("register")}</h2>
        <form className="form-grid" onSubmit={handleSubmit((values) => mutation.mutate(values))}>
          <label className="field">
            <span>{t("email")}</span>
            <input type="email" {...register("email")} />
            {errors.email ? <small>{errors.email.message}</small> : null}
          </label>
          <label className="field">
            <span>{t("password")}</span>
            <input type="password" {...register("password")} />
            {errors.password ? <small>{errors.password.message}</small> : null}
          </label>
          <label className="field">
            <span>{t("confirmPassword")}</span>
            <input type="password" {...register("confirmPassword")} />
            {errors.confirmPassword ? <small>{errors.confirmPassword.message}</small> : null}
          </label>
          {mutation.isError ? <p className="form-error">{mutation.error.message}</p> : null}
          <Button disabled={mutation.isPending} type="submit">
            {t("createAccount")}
          </Button>
        </form>
        <div className="auth-divider">{t("or")}</div>
        <GoogleSignInButton />
        <Link className="auth-link" to="/login">
          {t("authLoginLink")}
        </Link>
      </section>
    </div>
  );
}
