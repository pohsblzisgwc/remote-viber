import React from 'react';
import ReactDOM from 'react-dom/client';
import App from './App';
import './styles/index.css';

class RootErrorBoundary extends React.Component {
  constructor(props) {
    super(props);
    this.state = { hasError: false, error: null };
  }
  static getDerivedStateFromError(error) {
    return { hasError: true, error };
  }
  componentDidCatch(error, errorInfo) {
    console.error('RootErrorBoundary caught error:', error, errorInfo);
  }
  render() {
    if (this.state.hasError) {
      return (
        <div className="h-[100dvh] w-full flex items-center justify-center bg-[#090d16] text-slate-100 p-6">
          <div className="max-w-md w-full bg-slate-900/90 border border-red-500/40 rounded-2xl p-6 shadow-2xl backdrop-blur-md text-center space-y-4">
            <div className="w-12 h-12 mx-auto rounded-full bg-red-500/20 flex items-center justify-center text-red-400 text-2xl font-bold">
              ✕
            </div>
            <h2 className="text-lg font-bold text-slate-100">工作台界面渲染遇到异常</h2>
            <p className="text-xs text-slate-400 break-words font-mono bg-black/40 p-3 rounded-lg text-left">
              {this.state.error?.message || String(this.state.error)}
            </p>
            <button
              onClick={() => { sessionStorage.clear(); window.location.reload(); }}
              className="w-full py-2.5 px-4 rounded-xl bg-cyan-600 hover:bg-cyan-500 text-white font-semibold text-sm transition-all shadow-lg shadow-cyan-600/30"
            >
              重置缓存并重新载入
            </button>
          </div>
        </div>
      );
    }
    return this.props.children;
  }
}

ReactDOM.createRoot(document.getElementById('root')).render(
  <React.StrictMode>
    <RootErrorBoundary>
      <App />
    </RootErrorBoundary>
  </React.StrictMode>
);
