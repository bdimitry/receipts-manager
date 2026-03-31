import { createBrowserRouter, Navigate } from "react-router-dom";
import { LoginPage } from "../features/auth/pages/LoginPage";
import { RegisterPage } from "../features/auth/pages/RegisterPage";
import { DashboardPage } from "../features/dashboard/pages/DashboardPage";
import { ProfilePage } from "../features/profile/pages/ProfilePage";
import { PurchasesPage } from "../features/purchases/pages/PurchasesPage";
import { ReceiptDetailPage } from "../features/receipts/pages/ReceiptDetailPage";
import { ReceiptsPage } from "../features/receipts/pages/ReceiptsPage";
import { ReportsPage } from "../features/reports/pages/ReportsPage";
import { AppLayout } from "./layout/AppLayout";
import { ProtectedRoute } from "./layout/ProtectedRoute";

export const router = createBrowserRouter([
  {
    path: "/login",
    element: <LoginPage />,
  },
  {
    path: "/register",
    element: <RegisterPage />,
  },
  {
    element: <ProtectedRoute />,
    children: [
      {
        path: "/",
        element: <AppLayout />,
        children: [
          {
            index: true,
            element: <DashboardPage />,
          },
          {
            path: "purchases",
            element: <PurchasesPage />,
          },
          {
            path: "receipts",
            element: <ReceiptsPage />,
          },
          {
            path: "receipts/:id",
            element: <ReceiptDetailPage />,
          },
          {
            path: "reports",
            element: <ReportsPage />,
          },
          {
            path: "profile",
            element: <ProfilePage />,
          },
          {
            path: "*",
            element: <Navigate to="/" replace />,
          },
        ],
      },
    ],
  },
  {
    path: "*",
    element: <Navigate to="/login" replace />,
  },
]);
