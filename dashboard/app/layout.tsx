import type { Metadata } from "next";
import "./globals.css";
import { Sidebar } from "@/components/Sidebar";

export const metadata: Metadata = {
  title: "LogSlim",
  description: "Lossless log compression engine",
};

export default function RootLayout({ children }: { children: React.ReactNode }) {
  return (
    <html lang="en">
      <body className="bg-[#0a0a0a] text-[#e5e7eb]">
        <Sidebar />
        <main className="ml-52 min-h-screen p-8">
          {children}
        </main>
      </body>
    </html>
  );
}
